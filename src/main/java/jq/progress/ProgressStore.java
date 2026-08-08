package jq.progress;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 学習の進捗を progress.json に保存する。
 *
 * 保持するもの:
 *  - クリア済みの問題（クリア日、使ったヒント数、提出回数）
 *  - 問題ごとに最後に書いたコード（再訪時に復元する）
 *  - 確認クイズで選んだ選択肢（正解かどうかは保存せず、出題側と突き合わせて毎回求める）
 *  - 何か1問クリアした日付の集合（連続学習日数の計算に使う）
 *  - Java Café の売上・累計提供数・設備・受取済みボーナス
 *
 * 1レッスンに練習問題が複数あるので、★もコードもヒントも **問題ごと** に持つ。
 * キーは {@code レッスンID#連番}（{@link jq.content.Lesson#taskKey}）。
 * クイズだけはレッスン単位なので {@code レッスンID#クイズ番号} を別のマップに持つ。
 *
 * サーバは複数リクエストを並行に処理するので、状態変更は全て synchronized で守る。
 */
public final class ProgressStore {

    private static final int CAFE_ECONOMY_VERSION = 2;
    private static final int CUP_PRICE = 500;

    private final Path file;

    /** 問題キー -> クリア情報 */
    private final Map<String, Cleared> cleared = new LinkedHashMap<>();
    /** 問題キー -> 最後に書いたコード */
    private final Map<String, String> codes = new LinkedHashMap<>();
    /** 問題キー -> 開示済みヒント数 */
    private final Map<String, Integer> hintsRevealed = new LinkedHashMap<>();
    /** 問題キー -> 提出回数 */
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    /** 問題キー -> これまでで最も多く通ったケース数（画面の「通過したテストケース」に使う） */
    private final Map<String, Integer> bestPassed = new LinkedHashMap<>();
    /** "レッスンID#クイズ番号" -> 選んだ選択肢の番号 */
    private final Map<String, Integer> quizChoices = new LinkedHashMap<>();
    /** 何かをクリアした日付 */
    private final Set<String> clearDates = new TreeSet<>();

    /** カフェで現在使える売上。設備を買うと減る。 */
    private long cafeCash;
    /** これまでに提供したコーヒー。減らない成長指標。 */
    private long cafeCups;
    /** 購入済み設備ID。 */
    private final Set<String> cafeUpgrades = new LinkedHashSet<>();
    /** 初回正解ボーナスを受け取ったクイズ。答え直しによる重複獲得を防ぐ。 */
    private final Set<String> rewardedQuizzes = new LinkedHashSet<>();
    /** 章制覇ボーナスを受け取った章。同時提出でも重複獲得させない。 */
    private final Set<String> rewardedChapters = new LinkedHashSet<>();

    public record CafeUpgrade(
            String id,
            String name,
            String emoji,
            String description,
            int cost,
            int unlockChapters,
            int tier,
            String effectType,
            int effectValue) {
    }

    public record CafeAward(long cash, long cups) {
        public static final CafeAward NONE = new CafeAward(0, 0);

        public CafeAward plus(CafeAward other) {
            return new CafeAward(cash + other.cash, cups + other.cups);
        }
    }

    public record PurchaseResult(boolean purchased, String error, CafeUpgrade upgrade) {
    }

    private record CafeLevel(int level, String title, int threshold, int cupsPerOrder) {
    }

    private static final List<CafeLevel> CAFE_LEVELS = List.of(
            new CafeLevel(1, "屋台カフェ", 0, 1),
            new CafeLevel(2, "街角のコーヒースタンド", 10, 2),
            new CafeLevel(3, "こだわりの小さな店", 30, 4),
            new CafeLevel(4, "人気カフェ", 80, 8),
            new CafeLevel(5, "大型ロースタリー", 180, 16),
            new CafeLevel(6, "Java Café チェーン", 400, 32),
            new CafeLevel(7, "世界的Javaカフェ", 850, 64));

    private static final List<CafeUpgrade> CAFE_UPGRADES = List.of(
            new CafeUpgrade("welcome_mat", "ウェルカムマット", "🟫",
                    "来店率を上げる · 注文売上 +2%", 1_000, 0, 1, "sales", 2),
            new CafeUpgrade("extra_mugs", "追加マグセット", "🥤",
                    "一度に出せる数を増やす · 毎注文 +1杯", 1_000, 0, 1, "cups", 1),
            new CafeUpgrade("stamp_card", "スタンプカード", "🎫",
                    "常連客を増やす · 章ボーナス +25%", 1_000, 0, 1, "chapter", 25),
            new CafeUpgrade("tip_jar", "小さなチップ瓶", "🫙",
                    "クイズを楽しむお客さんが増える · 正解チップ +100%", 1_000, 0, 1, "tips", 100),
            new CafeUpgrade("morning_playlist", "朝のプレイリスト", "🎵",
                    "毎日通いたくなる空間 · 連続1日ごと注文売上 +1%", 1_000, 0, 1, "streak", 1),

            new CafeUpgrade("signboard", "手書きの看板", "🪧",
                    "店を見つけてもらいやすくする · 注文売上 +4%", 4_000, 1, 2, "sales", 4),
            new CafeUpgrade("hand_grinder", "手挽きミル", "🫘",
                    "抽出を並行できる · 毎注文 +1杯", 4_000, 1, 2, "cups", 1),
            new CafeUpgrade("dripper", "ドリップスタンド", "🫗",
                    "繁忙時の抽出を安定させる · 章ボーナス +30%", 4_000, 1, 2, "chapter", 30),
            new CafeUpgrade("cookie_plate", "試食クッキープレート", "🍪",
                    "正解を祝うひと口サービス · 正解チップ +150%", 4_000, 1, 2, "tips", 150),
            new CafeUpgrade("window_seat", "窓際の指定席", "🪟",
                    "毎日の常連席をつくる · 連続1日ごと注文売上 +1%", 4_000, 1, 2, "streak", 1),

            new CafeUpgrade("grinder", "セラミックグラインダー", "⚙️",
                    "豆の品質で客単価アップ · 注文売上 +7%", 15_000, 2, 3, "sales", 7),
            new CafeUpgrade("brew_station", "第2抽出ステーション", "🫖",
                    "二つの注文を同時に作る · 毎注文 +2杯", 15_000, 2, 3, "cups", 2),
            new CafeUpgrade("showcase", "焼き菓子ケース", "🧁",
                    "章末のまとめ買いを増やす · 章ボーナス +40%", 15_000, 2, 3, "chapter", 40),
            new CafeUpgrade("latte_art", "ラテアート練習台", "🎨",
                    "正解祝いの一杯を特別に · 正解チップ +200%", 15_000, 2, 3, "tips", 200),
            new CafeUpgrade("study_table", "学習者の大テーブル", "📚",
                    "学び続ける常連が集まる · 連続1日ごと注文売上 +2%", 15_000, 2, 3, "streak", 2),

            new CafeUpgrade("espresso", "エスプレッソマシン", "☕",
                    "高単価メニューを提供 · 注文売上 +10%", 60_000, 4, 4, "sales", 10),
            new CafeUpgrade("seats", "くつろぎテーブル", "🪑",
                    "同時に迎えられる客を増やす · 毎注文 +4杯", 60_000, 4, 4, "cups", 4),
            new CafeUpgrade("weekend_event", "週末コーヒーイベント", "🎪",
                    "章末にお客さんを集める · 章ボーナス +50%", 60_000, 4, 4, "chapter", 50),
            new CafeUpgrade("dessert_pairing", "デザートペアリング", "🍰",
                    "知識と味の組み合わせを祝う · 正解チップ +300%", 60_000, 4, 4, "tips", 300),
            new CafeUpgrade("loyalty_board", "常連ネームボード", "🏷️",
                    "連続来店を店内で称える · 連続1日ごと注文売上 +2%", 60_000, 4, 4, "streak", 2),

            new CafeUpgrade("roaster", "小型ロースター", "🔥",
                    "自家焙煎でブランド化 · 注文売上 +15%", 200_000, 6, 5, "sales", 15),
            new CafeUpgrade("kitchen", "増設キッチン", "🍳",
                    "大量の注文へ対応 · 毎注文 +8杯", 200_000, 6, 5, "cups", 8),
            new CafeUpgrade("terrace", "テラス貸切プラン", "⛱️",
                    "章末に団体客を呼ぶ · 章ボーナス +60%", 200_000, 6, 5, "chapter", 60),
            new CafeUpgrade("tasting_flight", "飲み比べフライト", "🥃",
                    "正解後の体験価値を上げる · 正解チップ +450%", 200_000, 6, 5, "tips", 450),
            new CafeUpgrade("daily_roast_log", "本日の焙煎ログ", "📋",
                    "学習と焙煎を毎日記録 · 連続1日ごと注文売上 +3%", 200_000, 6, 5, "streak", 3),

            new CafeUpgrade("pos", "POSレジ", "🖥️",
                    "販売データで価格を最適化 · 注文売上 +20%", 600_000, 9, 6, "sales", 20),
            new CafeUpgrade("mobile", "モバイルオーダー端末", "📱",
                    "店外からの注文も受ける · 毎注文 +16杯", 600_000, 9, 6, "cups", 16),
            new CafeUpgrade("subscription", "豆の定期便", "📦",
                    "章末に定期購入が入る · 章ボーナス +75%", 600_000, 9, 6, "chapter", 75),
            new CafeUpgrade("barista_school", "バリスタ講座", "🎓",
                    "正解の価値を伝える接客 · 正解チップ +650%", 600_000, 9, 6, "tips", 650),
            new CafeUpgrade("commuter_pass", "常連パスポート", "🪪",
                    "日々の来店を習慣化 · 連続1日ごと注文売上 +3%", 600_000, 9, 6, "streak", 3),

            new CafeUpgrade("second_store", "2号店", "🏪",
                    "別の街にもブランドを広げる · 注文売上 +30%", 1_800_000, 12, 7, "sales", 30),
            new CafeUpgrade("delivery", "デリバリー車両", "🛵",
                    "広い地域の注文へ対応 · 毎注文 +32杯", 1_800_000, 12, 7, "cups", 32),
            new CafeUpgrade("factory", "焙煎ファクトリー", "🏭",
                    "章末に全店へ豆を出荷 · 章ボーナス +100%", 1_800_000, 12, 7, "chapter", 100),
            new CafeUpgrade("vip_counter", "VIPカウンター", "💎",
                    "クイズ好きの特別席 · 正解チップ +900%", 1_800_000, 12, 7, "tips", 900),
            new CafeUpgrade("daily_newsletter", "毎朝のニュースレター", "📰",
                    "常連へ学びの話題を届ける · 連続1日ごと注文売上 +4%", 1_800_000, 12, 7, "streak", 4),

            new CafeUpgrade("flagship_store", "フラッグシップ店", "🏛️",
                    "街の名所になり客単価上昇 · 注文売上 +40%", 5_000_000, 16, 8, "sales", 40),
            new CafeUpgrade("robot_barista", "ロボットバリスタ", "🤖",
                    "大量注文を正確に抽出 · 毎注文 +64杯", 5_000_000, 16, 8, "cups", 64),
            new CafeUpgrade("catering", "法人ケータリング", "🚚",
                    "章末に大型注文を獲得 · 章ボーナス +130%", 5_000_000, 16, 8, "chapter", 130),
            new CafeUpgrade("concierge", "コーヒーコンシェルジュ", "🤵",
                    "知識に合わせて一杯を提案 · 正解チップ +1200%", 5_000_000, 16, 8, "tips", 1_200),
            new CafeUpgrade("habit_app", "学習習慣アプリ", "📲",
                    "毎日の来店を楽しく通知 · 連続1日ごと注文売上 +5%", 5_000_000, 16, 8, "streak", 5),

            new CafeUpgrade("airport_store", "空港ラウンジ店", "✈️",
                    "世界の旅行客へ販売 · 注文売上 +55%", 12_000_000, 21, 9, "sales", 55),
            new CafeUpgrade("smart_kitchen", "スマートキッチン", "🦾",
                    "全工程を自動連携 · 毎注文 +100杯", 12_000_000, 21, 9, "cups", 100),
            new CafeUpgrade("coffee_festival", "都市コーヒーフェス", "🎆",
                    "章末に街じゅうを集客 · 章ボーナス +170%", 12_000_000, 21, 9, "chapter", 170),
            new CafeUpgrade("members_lounge", "会員制ラウンジ", "🛋️",
                    "正解を語り合う上質な席 · 正解チップ +1600%", 12_000_000, 21, 9, "tips", 1_600),
            new CafeUpgrade("mentor_club", "朝活メンタークラブ", "🌅",
                    "仲間と毎日学び続ける · 連続1日ごと注文売上 +6%", 12_000_000, 21, 9, "streak", 6),

            new CafeUpgrade("global_brand", "グローバルブランド", "🌍",
                    "世界共通の一杯へ · 注文売上 +75%", 30_000_000, 27, 10, "sales", 75),
            new CafeUpgrade("coffee_lab", "全自動コーヒーラボ", "🧪",
                    "研究設備で超大量抽出 · 毎注文 +160杯", 30_000_000, 27, 10, "cups", 160),
            new CafeUpgrade("world_expo", "ワールドコーヒーEXPO", "🎡",
                    "章末に世界規模の注文 · 章ボーナス +220%", 30_000_000, 27, 10, "chapter", 220),
            new CafeUpgrade("founders_club", "創業者クラブ", "👑",
                    "最高の学びへ最大級の祝福 · 正解チップ +2200%", 30_000_000, 27, 10, "tips", 2_200),
            new CafeUpgrade("learning_retreat", "学習リトリート", "🏝️",
                    "学びを生活の一部にする · 連続1日ごと注文売上 +7%", 30_000_000, 27, 10, "streak", 7));

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    public ProgressStore(Path file) {
        this.file = file;
        load();
    }

    // ------------------------------------------------------------------ read

    /** クリア済みの問題キー。 */
    public synchronized Set<String> clearedIds() {
        return new LinkedHashSet<>(cleared.keySet());
    }

    public synchronized String savedCode(String taskKey) {
        return codes.get(taskKey);
    }

    public synchronized int hintsRevealed(String taskKey) {
        return hintsRevealed.getOrDefault(taskKey, 0);
    }

    /** そのクイズに選んだ選択肢の番号。まだ答えていなければ null。 */
    public synchronized Integer quizChoice(String lessonId, int index) {
        return quizChoices.get(quizKey(lessonId, index));
    }

    /** その問題でこれまでに最も多く通ったケース数。一度も提出していなければ 0。 */
    public synchronized int bestPassed(String taskKey) {
        return bestPassed.getOrDefault(taskKey, 0);
    }

    /** 今日を含む連続学習日数。今日も昨日も学習していなければ 0。 */
    public synchronized int streak() {
        if (clearDates.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate cursor;
        if (clearDates.contains(today.toString())) {
            cursor = today;
        } else if (clearDates.contains(today.minusDays(1).toString())) {
            // 今日まだ解いていなくても、昨日までの連続記録は生きている
            cursor = today.minusDays(1);
        } else {
            return 0;
        }
        int count = 0;
        while (clearDates.contains(cursor.toString())) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /**
     * ブラウザへ渡す進捗。
     *
     * 画面が実際に使う★の数・連続日数・提出回数・カフェ状態だけを渡す。
     * 書きかけのコードは各問題の savedCode として別に載るため、ここで
     * codes を丸ごと足すと同じものを二重に送ることになる（問題が増えるほど重くなる）。
     */
    public synchronized Object toClientJson(int clearedChapters) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("starCount", cleared.size());
        m.put("streak", streak());
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("cafe", cafeToClientJson(clearedChapters));
        return m;
    }

    private Object cafeToClientJson(int clearedChapters) {
        CafeLevel level = currentCafeLevel();
        CafeLevel nextLevel = level.level() < CAFE_LEVELS.size()
                ? CAFE_LEVELS.get(level.level())
                : null;
        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("cupPrice", CUP_PRICE);
        cafe.put("level", level.level());
        cafe.put("levelTitle", level.title());
        cafe.put("levelThreshold", level.threshold());
        cafe.put("nextLevelCups", nextLevel == null ? null : nextLevel.threshold());
        cafe.put("cupsPerOrder", level.cupsPerOrder());
        cafe.put("bonusPercent", cafeBonusPercent());
        cafe.put("salesBonusPercent", cafeSalesBonusPercent());
        cafe.put("streakBonusPercent", cafeStreakBonusPercent());
        cafe.put("extraCups", cafeExtraCups());
        cafe.put("chapterBonusPercent", cafeChapterBonusPercent());
        cafe.put("quizTipPercent", cafeQuizTipPercent());
        cafe.put("clearedChapters", clearedChapters);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));

        List<Object> upgrades = new ArrayList<>();
        for (CafeUpgrade u : CAFE_UPGRADES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.id());
            item.put("name", u.name());
            item.put("emoji", u.emoji());
            item.put("description", u.description());
            item.put("cost", u.cost());
            item.put("unlockChapters", u.unlockChapters());
            item.put("tier", u.tier());
            item.put("effectType", u.effectType());
            item.put("effectValue", u.effectValue());
            item.put("owned", cafeUpgrades.contains(u.id()));
            upgrades.add(item);
        }
        cafe.put("upgrades", upgrades);
        return cafe;
    }

    /** 初クリアした注文の報酬。客単価・連続学習ボーナスは売上へ掛ける。 */
    public synchronized CafeAward rewardTask() {
        // テストケース数ではなく店舗の集客力で販売数を増やす。
        // 最初は1杯だが、レベルが上がるたび倍になり、成長を数字で実感できる。
        long cups = cupsPerOrderWithUpgrades();
        long baseCash = cups * CUP_PRICE;
        long cash = baseCash * (100L + cafeBonusPercent()) / 100L;
        return addCafeReward(cash, cups);
    }

    /** 章を初めて制覇したときのまとまったボーナス。 */
    public synchronized CafeAward rewardChapter(String chapterId) {
        if (!rewardedChapters.add(chapterId)) {
            return CafeAward.NONE;
        }
        long baseCups = cupsPerOrderWithUpgrades() * 5L;
        long cups = baseCups * (100L + cafeChapterBonusPercent()) / 100L;
        long cash = cups * CUP_PRICE * (100L + cafeBonusPercent()) / 100L;
        return addCafeReward(cash, cups);
    }

    /** クイズに初めて正解したときだけチップを付ける。 */
    public synchronized CafeAward rewardQuiz(String lessonId, int index) {
        String key = quizKey(lessonId, index);
        if (!rewardedQuizzes.add(key)) {
            return CafeAward.NONE;
        }
        long cash = 100L * (100L + cafeQuizTipPercent()) / 100L;
        return addCafeReward(cash, 0);
    }

    /** 設備を購入する。残高・解放条件・重複購入をここで一括判定する。 */
    public synchronized PurchaseResult purchaseCafeUpgrade(String id, int clearedChapters) {
        CafeUpgrade upgrade = CAFE_UPGRADES.stream()
                .filter(u -> u.id().equals(id))
                .findFirst()
                .orElse(null);
        if (upgrade == null) {
            return new PurchaseResult(false, "その設備はありません", null);
        }
        if (cafeUpgrades.contains(id)) {
            return new PurchaseResult(false, "その設備は購入済みです", upgrade);
        }
        if (clearedChapters < upgrade.unlockChapters()) {
            return new PurchaseResult(false,
                    "章を" + upgrade.unlockChapters() + "個クリアすると解放されます", upgrade);
        }
        if (cafeCash < upgrade.cost()) {
            return new PurchaseResult(false, "売上が足りません", upgrade);
        }
        cafeCash -= upgrade.cost();
        cafeUpgrades.add(id);
        persist();
        return new PurchaseResult(true, null, upgrade);
    }

    private CafeAward addCafeReward(long cash, long cups) {
        cafeCash += cash;
        cafeCups += cups;
        persist();
        return new CafeAward(cash, cups);
    }

    private int cafeBonusPercent() {
        return cafeSalesBonusPercent() + cafeStreakBonusPercent();
    }

    private int cafeSalesBonusPercent() {
        return cafeEffectTotal("sales");
    }

    /** 連続日数による注文売上アップ。長期離脱で差が開きすぎないよう7日を上限にする。 */
    private int cafeStreakBonusPercent() {
        return cafeEffectTotal("streak") * Math.min(streak(), 7);
    }

    private int cafeExtraCups() {
        return cafeEffectTotal("cups");
    }

    private int cafeChapterBonusPercent() {
        return cafeEffectTotal("chapter");
    }

    private int cafeQuizTipPercent() {
        return cafeEffectTotal("tips");
    }

    private int cafeEffectTotal(String effectType) {
        int total = 0;
        for (CafeUpgrade upgrade : CAFE_UPGRADES) {
            if (cafeUpgrades.contains(upgrade.id()) && upgrade.effectType().equals(effectType)) {
                total += upgrade.effectValue();
            }
        }
        return total;
    }

    private long cupsPerOrderWithUpgrades() {
        return currentCafeLevel().cupsPerOrder() + cafeExtraCups();
    }

    private CafeLevel currentCafeLevel() {
        CafeLevel current = CAFE_LEVELS.get(0);
        for (CafeLevel level : CAFE_LEVELS) {
            if (cafeCups >= level.threshold()) {
                current = level;
            }
        }
        return current;
    }

    // ----------------------------------------------------------------- write

    public synchronized void saveCode(String taskKey, String code) {
        if (code == null) {
            return;
        }
        codes.put(taskKey, code);
        persist();
    }

    public synchronized int recordAttempt(String taskKey) {
        int n = attempts.merge(taskKey, 1, Integer::sum);
        persist();
        return n;
    }

    /**
     * 採点結果のうち「通ったケース数」を記録する。
     *
     * これまでの最高記録だけを残す。あと一歩まで来ていた人の記録が、
     * その後の失敗した提出で下がってしまわないようにするため。
     */
    public synchronized void recordPassed(String taskKey, int passed) {
        bestPassed.merge(taskKey, passed, Math::max);
        persist();
    }

    /**
     * クリアを記録する。
     *
     * @return 今回はじめてクリアしたなら true（★獲得の演出に使う）
     */
    public synchronized boolean markCleared(String taskKey) {
        boolean isNew = !cleared.containsKey(taskKey);
        String today = LocalDate.now().toString();
        if (isNew) {
            cleared.put(taskKey, new Cleared(
                    today,
                    hintsRevealed.getOrDefault(taskKey, 0),
                    attempts.getOrDefault(taskKey, 1)));
        }
        clearDates.add(today);
        persist();
        return isNew;
    }

    /** ヒントを1つ開示したことを記録し、開示済み総数を返す。 */
    public synchronized int revealHint(String taskKey, int index) {
        int current = hintsRevealed.getOrDefault(taskKey, 0);
        int next = Math.max(current, index + 1);
        hintsRevealed.put(taskKey, next);
        persist();
        return next;
    }

    /** クイズの回答を記録する（答え直したら上書きする）。 */
    public synchronized void recordQuiz(String lessonId, int index, int choice) {
        quizChoices.put(quizKey(lessonId, index), choice);
        persist();
    }

    /** 進捗を全て消す。 */
    public synchronized void resetAll() {
        cleared.clear();
        codes.clear();
        hintsRevealed.clear();
        attempts.clear();
        bestPassed.clear();
        quizChoices.clear();
        clearDates.clear();
        cafeCash = 0;
        cafeCups = 0;
        cafeUpgrades.clear();
        rewardedQuizzes.clear();
        rewardedChapters.clear();
        persist();
    }

    private static String quizKey(String lessonId, int index) {
        return lessonId + "#" + index;
    }

    /**
     * 昔の進捗ファイルのキーを問題キーに読み替える。
     *
     * 1レッスン1問だった頃はレッスンIDそのものがキーだった（"5-2"）。
     * いまは問題ごとに "5-2#1" を使うので、"#" を含まない古いキーを1問目として扱う。
     * こうしないと、これまでの★が全部消えたように見えてしまう。
     */
    private static String migrateKey(String key) {
        return key.contains("#") ? key : key + "#1";
    }

    // ------------------------------------------------------------ 永続化本体

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            Map<String, Object> root = MiniJson.parseObject(text);
            boolean hasCafeState = root.get("cafe") instanceof Map;

            MiniJson.obj(root, "cleared").forEach((id, v) -> {
                Map<String, Object> c = MiniJson.asObj(v);
                cleared.put(migrateKey(id), new Cleared(
                        MiniJson.str(c, "clearedAt", LocalDate.now().toString()),
                        MiniJson.intOf(c, "hintsUsed", 0),
                        MiniJson.intOf(c, "attempts", 1)));
            });
            MiniJson.obj(root, "codes").forEach((id, v) -> {
                if (v instanceof String s) {
                    codes.put(migrateKey(id), s);
                }
            });
            MiniJson.obj(root, "hintsRevealed").forEach((id, v) -> {
                if (v instanceof Number n) {
                    hintsRevealed.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "attempts").forEach((id, v) -> {
                if (v instanceof Number n) {
                    attempts.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "bestPassed").forEach((id, v) -> {
                if (v instanceof Number n) {
                    bestPassed.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "quizChoices").forEach((key, v) -> {
                if (v instanceof Number n) {
                    quizChoices.put(key, n.intValue());
                }
            });
            for (Object o : MiniJson.list(root, "clearDates")) {
                if (o instanceof String s && isDate(s)) {
                    clearDates.add(s);
                }
            }

            if (hasCafeState) {
                Map<String, Object> cafe = MiniJson.obj(root, "cafe");
                cafeCash = longOf(cafe, "cash", 0);
                cafeCups = longOf(cafe, "cups", 0);
                int economyVersion = MiniJson.intOf(cafe, "economyVersion", 1);
                if (economyVersion < CAFE_ECONOMY_VERSION) {
                    // 初版は1杯ほぼ10円だったため、平均500円の新レートへ換算する。
                    cafeCash *= 50L;
                }
                for (Object o : MiniJson.list(cafe, "ownedUpgrades")) {
                    if (o instanceof String s && isKnownUpgrade(s)) {
                        cafeUpgrades.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "rewardedQuizzes")) {
                    if (o instanceof String s) {
                        rewardedQuizzes.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "rewardedChapters")) {
                    if (o instanceof String s) {
                        rewardedChapters.add(s);
                    }
                }
            } else {
                // カフェ機能追加前から学んでいた人の★を無かったことにしない。
                // 過去のケース数は進捗ファイルだけでは分からないため、控えめな固定報酬で移行する。
                cafeCash = cleared.size() * 12L * CUP_PRICE;
                cafeCups = cleared.size() * 12L;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("進捗ファイルを読めません: " + file, e);
        } catch (RuntimeException e) {
            // 壊れたファイルで起動できなくなるのは困るので、退避して作り直す
            System.err.println("進捗ファイルが壊れているようです (" + e.getMessage() + ")。"
                    + file.getFileName() + ".broken に退避して作り直します。");
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 退避に失敗しても、以降の persist() で上書きされる
            }
            cleared.clear();
            codes.clear();
            hintsRevealed.clear();
            attempts.clear();
            bestPassed.clear();
            quizChoices.clear();
            clearDates.clear();
            cafeCash = 0;
            cafeCups = 0;
            cafeUpgrades.clear();
            rewardedQuizzes.clear();
            rewardedChapters.clear();
        }
    }

    /** 一時ファイルへ書いてから置き換える（書き込み中に落ちても壊れないように）。 */
    private void persist() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MiniJson.write(toJsonRaw()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("進捗を保存できませんでした: " + e.getMessage());
        }
    }

    /** 保存用（streak / starCount のような派生値は保存しない）。 */
    private Object toJsonRaw() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> clearedJson = new LinkedHashMap<>();
        cleared.forEach((id, c) -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("clearedAt", c.clearedAt());
            cm.put("hintsUsed", c.hintsUsed());
            cm.put("attempts", c.attempts());
            clearedJson.put(id, cm);
        });
        m.put("cleared", clearedJson);
        m.put("codes", new LinkedHashMap<>(codes));
        m.put("hintsRevealed", new LinkedHashMap<>(hintsRevealed));
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("bestPassed", new LinkedHashMap<>(bestPassed));
        m.put("quizChoices", new LinkedHashMap<>(quizChoices));
        m.put("clearDates", new ArrayList<>(clearDates));

        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("economyVersion", CAFE_ECONOMY_VERSION);
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("rewardedQuizzes", new ArrayList<>(rewardedQuizzes));
        cafe.put("rewardedChapters", new ArrayList<>(rewardedChapters));
        m.put("cafe", cafe);
        return m;
    }

    private static long longOf(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? Math.max(0L, n.longValue()) : fallback;
    }

    private static boolean isKnownUpgrade(String id) {
        return CAFE_UPGRADES.stream().anyMatch(u -> u.id().equals(id));
    }

    private static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
