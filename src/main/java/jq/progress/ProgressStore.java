package jq.progress;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 *
 * 保存はファイル全体の書き直しになるため、変更のたびには書かない。
 * {@link #saveSoon()}（★や購入など）と {@link #saveEventually()}（自動売上のtick）で
 * 溜めて、{@link #flushNow()} がまとめて1回書く。終了時は {@code jq.App} の
 * シャットダウンフックが最後に {@link #flushNow()} を呼ぶ。
 */
public final class ProgressStore {

    // 15: 教材が全547問へ増えたぶん、終盤の出店費を上げて投資率を戻した
    private static final int CAFE_ECONOMY_VERSION = 15;
    private static final int CUP_PRICE = 500;
    private static final int MAX_CAFE_STORES = 512;
    private static final long FIRST_EXPANSION_COST = 2_500L;
    /**
     * 5店舗以降の出店費（規模の三乗に掛ける係数）。終盤の主なコイン消費先。
     *
     * <p><b>問題数を増やしたら、この値を見直して {@code tools/simulate-cafe.sh} を通すこと。</b>
     * 生涯売上はブランド倍率（下の定数）が設備効果へ掛かるぶん、問題数に対して
     * 加速して伸びる。一方で購入費の合計は問題数と無関係なので、教材を増やすだけで
     * 投資率（購入費 ÷ 生涯売上）が下がり、目標の25〜45%を割る。
     * 売上側を削ると学習の報酬感が変わるため、終盤の消費先であるここで吸収している。</p>
     *
     * <p>実測: 全509問で8,500だと22.9%、全516問で15,000だと27.7%、
     * 全532問で25,000だと25.92%、全547問で25,000だと18.49%まで落ちた。
     * 38,000へ上げて終盤の消費先で吸収した。</p>
     */
    private static final long EXPANSION_CUBIC_COST = 38_000L;
    /** 完成した章の問題1問あたりのブランド成長。全547問で約x10.30になる。 */
    private static final int BRAND_GROWTH_BASIS_POINTS_PER_TASK = 170;
    private static final int LUCKY_COIN_CHANCE_PERCENT = 12;
    private static final int TASK_COMBO_INTERVAL = 5;
    /** 一気読みのしおりが効く間隔。コンボ(5)・レシピ(7)と重ならない値にする。 */
    private static final int TASK_BEAT_INTERVAL = 4;
    /** 粘りのドリッパーが「粘った」とみなす提出回数。 */
    private static final int RETRY_BONUS_ATTEMPTS = 5;
    /** 連続学習ボーナスの既定の上限日数。皆勤の日めくりだけがこれを広げる。 */
    private static final int STREAK_BONUS_CAP_DAYS = 7;
    /** 自動売上は、次に★を取るまで現在の問題報酬の50%まで。待つ方が得になるのを防ぐ。 */
    private static final int PASSIVE_CASH_CAP_BASIS_POINTS = 5_000;
    /** 通常設備Rank 1〜12の基準★。5系統へ0〜8の差を付け、一斉解放を避ける。 */
    private static final int[] EQUIPMENT_REQUIRED_STARS =
            {0, 1, 14, 36, 65, 101, 144, 194, 251, 320, 386, 452, 497};
    /** ★の進行に応じて段階的に広がる店舗上限。 */
    private static final int[] STORE_UNLOCK_STARS =
            {4, 22, 57, 101, 144, 187, 230, 270, 310, 345, 385, 425, 458, 483, 502};
    private static final int[] STORE_LIMITS =
            {2, 3, 5, 8, 12, 18, 27, 41, 62, 93, 140, 210, 315, 473, MAX_CAFE_STORES};
    /** ブラウザのタイマー停止を「放置中の売上」として誤加算しないための1回あたり上限。 */
    private static final long MAX_PASSIVE_TICK_MILLIS = 10_000L;

    /**
     * ★の獲得や購入のような「失うと痛い変更」を書き出すまでの待ち時間。
     *
     * 保存はファイル全体の書き直しなので、変更のたびに書くと自動保存（打鍵0.8秒後）が
     * そのままディスク書き込みになる。少しだけ待ってまとめると、続けて起きる変更が
     * 1回の書き込みに畳まれる。
     */
    private static final long SAVE_DELAY_MS = 1_000L;

    /**
     * {@link #saveEventually()} で溜めた変更を書き出す間隔。
     *
     * 自動売上のtickは2.5秒ごとに届く（{@code web/app.js} の CAFE_PASSIVE_INTERVAL_MS）。
     * これを毎回ディスクに書くと、アプリを開いているだけでファイル全体の書き直しが
     * 延々と続く。tickぶんの売上は次のtickで作り直せる程度のものなので、
     * この間隔でまとめて書けば十分（最悪でもこの秒数ぶんの自動売上しか失われない）。
     */
    private static final long TRICKLE_SAVE_INTERVAL_SEC = 30L;

    private final Path file;

    /** 書き込みを直列化する錠。{@code this} より外側で取る（取る順序を逆にしないこと）。 */
    private final Object writeLock = new Object();

    /** ディスクに書けていない変更があるか。 */
    private boolean dirty;

    /** {@link #SAVE_DELAY_MS} 後の書き出しを予約済みか。二重に予約しないための印。 */
    private boolean saveScheduled;

    /** 遅延書き出し用。1本のデーモンスレッドなので、書き出しが交錯しない。 */
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jq-progress-save");
        t.setDaemon(true);
        return t;
    });

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
    /** これまでに獲得したコイン。支出しても減らず、スペシャルアイテムの発見条件になる。 */
    private long cafeLifetimeCash;
    /** 報酬を受け取った回数。再起動によるラッキー判定の引き直しを防ぐ。 */
    private long cafeRewardSequence;
    /** 問題クリア報酬を受け取った回数。コンボ報酬の進行を保存する。 */
    private long cafeTaskRewardCount;
    /** 最後に★を獲得してから受け取った自動売上。上限をリロードで引き直さないため保存する。 */
    private long cafePassiveCashSinceTask;
    /** 現在営業している店舗数。出店するたび、全店ぶんの注文を同時に受ける。 */
    private int cafeStores = 1;
    /** 購入済み設備ID。 */
    private final Set<String> cafeUpgrades = new LinkedHashSet<>();
    /** 購入済みの自動営業設備ID。最上位の1台だけが稼働する。 */
    private final Set<String> cafeAutomationUpgrades = new LinkedHashSet<>();
    /** 所持しているスペシャルアイテムID。 */
    private final Set<String> cafeItems = new LinkedHashSet<>();
    /** アイテム画面で確認済みのスペシャルアイテムID。新発見の通知を再表示しないため保存する。 */
    private final Set<String> cafeSeenItems = new LinkedHashSet<>();
    /** 達成済みのアイテム解放条件。いちど達成したら、あとで崩れても外さない。 */
    private final Set<String> cafeAchievements = new LinkedHashSet<>();
    /** 確認クイズを初回から連続で正解している数。間違えると0へ戻る。 */
    private int cafeQuizFirstStreak;
    /** 初回正解ボーナスを受け取ったクイズ。答え直しによる重複獲得を防ぐ。 */
    private final Set<String> rewardedQuizzes = new LinkedHashSet<>();
    /** 章制覇ボーナスを受け取った章。同時提出でも重複獲得させない。 */
    private final Set<String> rewardedChapters = new LinkedHashSet<>();
    /** 自動売上の画面セッション。永続化せず、再起動・オフライン中は一切加算しない。 */
    private String cafePassiveSessionId;
    private long cafePassiveLastTickNanos;
    /** ratePerMinute * elapsedMillis を60秒で割った端数。 */
    private long cafePassiveRemainder;

    public record CafeUpgrade(
            String id,
            String name,
            String emoji,
            String description,
            long cost,
            int tier,
            String effectType,
            int effectValue) {
    }

    public record CafeAward(long cash, long cups, List<String> itemEvents) {
        public static final CafeAward NONE = new CafeAward(0, 0, List.of());

        public CafeAward plus(CafeAward other) {
            List<String> events = new ArrayList<>(itemEvents);
            events.addAll(other.itemEvents);
            return new CafeAward(cash + other.cash, cups + other.cups, List.copyOf(events));
        }
    }

    public record PurchaseResult(
            boolean purchased,
            String error,
            CafeUpgrade upgrade,
            CafeUpgrade replacedUpgrade) {
    }

    public record ExpansionResult(
            boolean expanded,
            String error,
            int previousStores,
            int addedStores,
            int storeCount,
            long cost) {
    }

    /**
     * スペシャルアイテム1つ。
     *
     * 解放のされ方は2通りある。{@code unlockAchievement} が空なら★数と累計コイン
     * （学習の節目）で、値があれば {@link #ACHIEVEMENT_NOTES} の達成条件で解放する。
     */
    public record CafeItem(
            String id,
            String name,
            String emoji,
            String description,
            long cost,
            int unlockStars,
            long unlockLifetimeCash,
            String unlockAchievement,
            String effectType,
            int effectValue) {

        boolean byAchievement() {
            return !unlockAchievement.isEmpty();
        }
    }

    public record ItemPurchaseResult(boolean purchased, String error, CafeItem item) {
    }

    public record CafeAutomation(
            String id,
            String name,
            String emoji,
            String description,
            long cost,
            int tier,
            int requiredStars,
            int rateBasisPointsPerMinute) {
    }

    public record AutomationPurchaseResult(
            boolean purchased,
            String error,
            CafeAutomation automation,
            CafeAutomation replacedAutomation) {
    }

    public record PassiveSalesResult(long cash, long cashPerMinute, boolean active) {
    }

    /** カフェ計算に必要な、教材側で算出した学習進捗。 */
    public record CafeLearningProgress(int clearedChapters, int masteredChapterTasks) {
    }

    private record CafeLevel(int level, String title, int threshold, int cupsPerOrder) {
    }

    private static final List<CafeLevel> CAFE_LEVELS = List.of(
            new CafeLevel(1, "屋台カフェ", 0, 1),
            new CafeLevel(2, "街角のコーヒースタンド", 6, 2),
            new CafeLevel(3, "こだわりの小さな店", 20, 4),
            new CafeLevel(4, "人気カフェ", 50, 8),
            new CafeLevel(5, "大型ロースタリー", 100, 16),
            new CafeLevel(6, "Java Café チェーン", 170, 32),
            new CafeLevel(7, "世界的Javaカフェ", 240, 64),
            new CafeLevel(8, "テック街区ロースタリー", 310, 96),
            new CafeLevel(9, "全国Java Café連合", 370, 160),
            new CafeLevel(10, "アジア太平洋チェーン", 420, 256),
            new CafeLevel(11, "世界開発者ラウンジ", 474, 384),
            new CafeLevel(12, "Java Café 殿堂", 505, 512));

    private static final List<CafeUpgrade> CAFE_UPGRADES = List.of(
            new CafeUpgrade("welcome_mat", "ウェルカムマット", "🟫",
                    "来店率を上げる · 注文売上 +2%", 1_000, 1, "sales", 2),
            new CafeUpgrade("extra_mugs", "追加マグセット", "🥤",
                    "一度に出せる数を増やす · 毎注文 +1杯", 2_000, 1, "cups", 1),
            new CafeUpgrade("stamp_card", "スタンプカード", "🎫",
                    "常連客を増やす · 章ボーナス +10%", 700, 1, "chapter", 10),
            new CafeUpgrade("tip_jar", "小さなチップ瓶", "🫙",
                    "クイズを楽しむお客さんが増える · 正解チップ +25%", 300, 1, "tips", 25),
            new CafeUpgrade("morning_playlist", "朝のプレイリスト", "🎵",
                    "毎日通いたくなる空間 · 連続1日ごと注文売上 +1%", 1_000, 1, "streak", 1),

            new CafeUpgrade("signboard", "手書きの看板", "🪧",
                    "店を見つけてもらいやすくする · 注文売上 +6%", 7_000, 2, "sales", 6),
            new CafeUpgrade("hand_grinder", "手挽きミル", "🫘",
                    "抽出を並行できる · 毎注文 +2杯", 14_000, 2, "cups", 2),
            new CafeUpgrade("dripper", "ドリップスタンド", "🫗",
                    "繁忙時の抽出を安定させる · 章ボーナス +20%", 4_900, 2, "chapter", 20),
            new CafeUpgrade("cookie_plate", "試食クッキープレート", "🍪",
                    "正解を祝うひと口サービス · 正解チップ +50%", 2_100, 2, "tips", 50),
            new CafeUpgrade("window_seat", "窓際の指定席", "🪟",
                    "毎日の常連席をつくる · 連続1日ごと注文売上 +2%", 7_000, 2, "streak", 2),

            new CafeUpgrade("grinder", "セラミックグラインダー", "⚙️",
                    "豆の品質で客単価アップ · 注文売上 +13%", 50_000, 3, "sales", 13),
            new CafeUpgrade("brew_station", "第2抽出ステーション", "🫖",
                    "二つの注文を同時に作る · 毎注文 +4杯", 100_000, 3, "cups", 4),
            new CafeUpgrade("showcase", "焼き菓子ケース", "🧁",
                    "章末のまとめ買いを増やす · 章ボーナス +30%", 35_000, 3, "chapter", 30),
            new CafeUpgrade("latte_art", "ラテアート練習台", "🎨",
                    "正解祝いの一杯を特別に · 正解チップ +80%", 15_000, 3, "tips", 80),
            new CafeUpgrade("study_table", "学習者の大テーブル", "📚",
                    "学び続ける常連が集まる · 連続1日ごと注文売上 +3%", 50_000, 3, "streak", 3),

            new CafeUpgrade("espresso", "エスプレッソマシン", "☕",
                    "高単価メニューを提供 · 注文売上 +23%", 300_000, 4, "sales", 23),
            new CafeUpgrade("seats", "くつろぎテーブル", "🪑",
                    "同時に迎えられる客を増やす · 毎注文 +8杯", 600_000, 4, "cups", 8),
            new CafeUpgrade("weekend_event", "週末コーヒーイベント", "🎪",
                    "章末にお客さんを集める · 章ボーナス +45%", 210_000, 4, "chapter", 45),
            new CafeUpgrade("dessert_pairing", "デザートペアリング", "🍰",
                    "知識と味の組み合わせを祝う · 正解チップ +110%", 90_000, 4, "tips", 110),
            new CafeUpgrade("loyalty_board", "常連ネームボード", "🏷️",
                    "連続来店を店内で称える · 連続1日ごと注文売上 +4%", 300_000, 4, "streak", 4),

            new CafeUpgrade("roaster", "小型ロースター", "🔥",
                    "自家焙煎でブランド化 · 注文売上 +38%", 1_500_000, 5, "sales", 38),
            new CafeUpgrade("kitchen", "増設キッチン", "🍳",
                    "大量の注文へ対応 · 毎注文 +16杯", 3_000_000, 5, "cups", 16),
            new CafeUpgrade("terrace", "テラス貸切プラン", "⛱️",
                    "章末に団体客を呼ぶ · 章ボーナス +60%", 1_050_000, 5, "chapter", 60),
            new CafeUpgrade("tasting_flight", "飲み比べフライト", "🥃",
                    "正解後の体験価値を上げる · 正解チップ +145%", 450_000, 5, "tips", 145),
            new CafeUpgrade("daily_roast_log", "本日の焙煎ログ", "📋",
                    "学習と焙煎を毎日記録 · 連続1日ごと注文売上 +5%", 1_500_000, 5, "streak", 5),

            new CafeUpgrade("pos", "POSレジ", "🖥️",
                    "販売データで価格を最適化 · 注文売上 +58%", 8_000_000, 6, "sales", 58),
            new CafeUpgrade("mobile", "モバイルオーダー端末", "📱",
                    "店外からの注文も受ける · 毎注文 +32杯", 16_000_000, 6, "cups", 32),
            new CafeUpgrade("subscription", "豆の定期便", "📦",
                    "章末に定期購入が入る · 章ボーナス +80%", 5_600_000, 6, "chapter", 80),
            new CafeUpgrade("barista_school", "バリスタ講座", "🎓",
                    "正解の価値を伝える接客 · 正解チップ +180%", 2_400_000, 6, "tips", 180),
            new CafeUpgrade("commuter_pass", "常連パスポート", "🪪",
                    "日々の来店を習慣化 · 連続1日ごと注文売上 +6%", 8_000_000, 6, "streak", 6),

            new CafeUpgrade("second_store", "フランチャイズ本部", "🏢",
                    "全店の販売戦略を統一する · 注文売上 +88%", 40_000_000, 7, "sales", 88),
            new CafeUpgrade("delivery", "デリバリー車両", "🛵",
                    "広い地域の注文へ対応 · 毎注文 +64杯", 80_000_000, 7, "cups", 64),
            new CafeUpgrade("factory", "焙煎ファクトリー", "🏭",
                    "章末に全店へ豆を出荷 · 章ボーナス +105%", 28_000_000, 7, "chapter", 105),
            new CafeUpgrade("vip_counter", "VIPカウンター", "💎",
                    "クイズ好きの特別席 · 正解チップ +220%", 12_000_000, 7, "tips", 220),
            new CafeUpgrade("daily_newsletter", "毎朝のニュースレター", "📰",
                    "常連へ学びの話題を届ける · 連続1日ごと注文売上 +7%", 40_000_000, 7, "streak", 7),

            new CafeUpgrade("flagship_store", "フラッグシップ店", "🏛️",
                    "街の名所になり客単価上昇 · 注文売上 +128%", 200_000_000, 8, "sales", 128),
            new CafeUpgrade("robot_barista", "ロボットバリスタ", "🤖",
                    "大量注文を正確に抽出 · 毎注文 +128杯", 400_000_000, 8, "cups", 128),
            new CafeUpgrade("catering", "法人ケータリング", "🚚",
                    "章末に大型注文を獲得 · 章ボーナス +135%", 140_000_000, 8, "chapter", 135),
            new CafeUpgrade("concierge", "コーヒーコンシェルジュ", "🤵",
                    "知識に合わせて一杯を提案 · 正解チップ +260%", 60_000_000, 8, "tips", 260),
            new CafeUpgrade("habit_app", "学習習慣アプリ", "📲",
                    "毎日の来店を楽しく通知 · 連続1日ごと注文売上 +8%", 200_000_000, 8, "streak", 8),

            new CafeUpgrade("airport_store", "空港ラウンジ店", "✈️",
                    "世界の旅行客へ販売 · 注文売上 +183%", 1_000_000_000L, 9, "sales", 183),
            new CafeUpgrade("smart_kitchen", "スマートキッチン", "🦾",
                    "全工程を自動連携 · 毎注文 +228杯", 2_000_000_000L, 9, "cups", 228),
            new CafeUpgrade("coffee_festival", "都市コーヒーフェス", "🎆",
                    "章末に街じゅうを集客 · 章ボーナス +170%", 700_000_000, 9, "chapter", 170),
            new CafeUpgrade("members_lounge", "会員制ラウンジ", "🛋️",
                    "正解を語り合う上質な席 · 正解チップ +300%", 300_000_000, 9, "tips", 300),
            new CafeUpgrade("mentor_club", "朝活メンタークラブ", "🌅",
                    "仲間と毎日学び続ける · 連続1日ごと注文売上 +9%", 1_000_000_000L, 9, "streak", 9),

            new CafeUpgrade("global_brand", "グローバルブランド", "🌍",
                    "世界共通の一杯へ · 注文売上 +258%", 5_000_000_000L, 10, "sales", 258),
            new CafeUpgrade("coffee_lab", "全自動コーヒーラボ", "🧪",
                    "研究設備で超大量抽出 · 毎注文 +388杯", 10_000_000_000L, 10, "cups", 388),
            new CafeUpgrade("world_expo", "ワールドコーヒーEXPO", "🎡",
                    "章末に世界規模の注文 · 章ボーナス +210%", 3_500_000_000L, 10, "chapter", 210),
            new CafeUpgrade("founders_club", "創業者クラブ", "👑",
                    "最高の学びへ最大級の祝福 · 正解チップ +350%", 1_500_000_000L, 10, "tips", 350),
            new CafeUpgrade("learning_retreat", "学習リトリート", "🏝️",
                    "学びを生活の一部にする · 連続1日ごと注文売上 +10%", 5_000_000_000L, 10, "streak", 10),

            new CafeUpgrade("quantum_campaign", "量子級ブランドキャンペーン", "🪐",
                    "開発者コミュニティ全体へ届ける · 注文売上 +360%", 25_000_000_000L, 11, "sales", 360),
            new CafeUpgrade("orbital_roastery", "軌道ロースタリー", "🛰️",
                    "軌道上の巨大設備で抽出 · 毎注文 +640杯", 50_000_000_000L, 11, "cups", 640),
            new CafeUpgrade("developer_summit", "世界開発者サミット", "🧑‍🚀",
                    "章末に世界の学習者が集う · 章ボーナス +260%", 17_500_000_000L, 11, "chapter", 260),
            new CafeUpgrade("knowledge_vault", "知識の宝物庫", "🏆",
                    "正解の知識を価値ある体験へ · 正解チップ +400%", 7_500_000_000L, 11, "tips", 400),
            new CafeUpgrade("learning_guild", "世界学習ギルド", "🤝",
                    "仲間と学ぶ文化を世界へ · 連続1日ごと注文売上 +11%", 25_000_000_000L, 11, "streak", 11),

            new CafeUpgrade("java_legacy", "Javaレガシー殿堂", "🏛️",
                    "積み重ねた学びを永続するブランドへ · 注文売上 +500%", 100_000_000_000L, 12, "sales", 500),
            new CafeUpgrade("planetary_brew", "惑星間ブリューシステム", "🚀",
                    "惑星規模の注文を同時抽出 · 毎注文 +1024杯", 200_000_000_000L, 12, "cups", 1_024),
            new CafeUpgrade("mastery_congress", "マスタリー世界会議", "🎓",
                    "全章の学びを祝う最大イベント · 章ボーナス +320%", 70_000_000_000L, 12, "chapter", 320),
            new CafeUpgrade("hall_of_fame_counter", "殿堂カウンター", "🥇",
                    "最高難度の正解を盛大に祝う · 正解チップ +450%", 30_000_000_000L, 12, "tips", 450),
            new CafeUpgrade("lifelong_academy", "生涯学習アカデミー", "♾️",
                    "学び続ける文化を完成させる · 連続1日ごと注文売上 +12%", 100_000_000_000L, 12, "streak", 12));

    private static final List<CafeItem> CAFE_ITEMS = List.of(
            new CafeItem("lucky_coin", "ラッキーコイン", "🪙",
                    "問題・章・クイズ報酬で12%の確率で獲得コインが2倍",
                    5_000L, 6, 10_000L, "", "lucky_double", 2),
            new CafeItem("golden_bean", "コンボスタンプ帳", "🗒️",
                    "問題を5問クリアするたび、その問題の獲得コインが必ず3倍",
                    40_000L, 20, 100_000L, "", "task_combo", 3),
            new CafeItem("quiz_crown", "ひらめきメガホン", "📣",
                    "確認クイズの初回正解チップが必ず5倍",
                    250_000L, 50, 500_000L, "", "quiz_multiplier", 5),
            new CafeItem("fortune_cat", "祝福のホールケーキ", "🎂",
                    "章を初めて制覇したときの獲得コインが必ず2倍",
                    2_000_000L, 100, 5_000_000L, "", "chapter_multiplier", 2),
            new CafeItem("fever_bell", "フランチャイズ地図", "🗺️",
                    "新店舗の出店費用がいつでも25%OFF",
                    20_000_000L, 170, 50_000_000L, "", "expansion_discount", 25),
            new CafeItem("java_relic", "マイスター工具箱", "🧰",
                    "すべての設備アップグレード費用がいつでも20%OFF",
                    200_000_000L, 240, 500_000_000L, "", "equipment_discount", 20),
            new CafeItem("rhythm_recipe", "7品目のレシピ帳", "📖",
                    "問題を7問クリアするたび、その問題の獲得コインが必ず2倍",
                    800_000_000L, 280, 2_000_000_000L, "", "task_rhythm", 2),
            new CafeItem("comeback_ticket", "おかえり優待券", "🎟️",
                    "連続学習が途切れても、注文売上は連続3日分から再開",
                    3_000_000_000L, 320, 10_000_000_000L, "", "streak_floor", 3),
            new CafeItem("brand_charter", "Java Caféブランド憲章", "📜",
                    "完成した章から育つブランド倍率へさらに+0.25",
                    10_000_000_000L, 360, 30_000_000_000L, "", "brand_bonus", 2_500),
            new CafeItem("quiz_festival_pass", "クイズフェス招待券", "🎟️",
                    "確認クイズの初回正解チップがさらに2倍",
                    30_000_000_000L, 400, 100_000_000_000L, "", "quiz_extra_multiplier", 2),
            new CafeItem("mastery_archive", "章マスタリーアーカイブ", "🗄️",
                    "章を初めて制覇したときの獲得コインがさらに50%増加",
                    80_000_000_000L, 440, 300_000_000_000L, "", "chapter_extra_percent", 50),
            new CafeItem("lifelong_trophy", "生涯学習トロフィー", "🏆",
                    "問題・章・クイズで得るすべての学習報酬が10%増加",
                    200_000_000_000L, 475, 800_000_000_000L, "", "mastery_bonus", 10),

            // ここから下は★ではなく「学習の仕方」で解放される。条件は ACHIEVEMENT_NOTES。
            new CafeItem("first_try_tamper", "一発仕上げのタンパー", "\uD83E\uDD4C",
                    "ヒントなし・1回の提出でクリアした問題の獲得コインが+20%",
                    15_000L, 0, 0L, "flawless_10", "first_try_percent", 20),
            new CafeItem("prep_pot", "仕込み用の大鍋", "\uD83C\uDF72",
                    "同じ日にクリアするほど当日の獲得コインが増える（1問ごと+1%、最大+15%）",
                    40_000L, 0, 0L, "same_day_15", "daily_ramp_percent", 15),
            new CafeItem("attendance_calendar", "皆勤の日めくり", "\uD83D\uDCC5",
                    "連続学習ボーナスの上限が7日から10日に広がる",
                    120_000L, 0, 0L, "streak_7", "streak_cap", 10),
            new CafeItem("quiz_bell", "早押しベル", "\uD83D\uDD14",
                    "確認クイズの初回正解チップがさらに2倍",
                    300_000L, 0, 0L, "quiz_streak_20", "quiz_bell_multiplier", 2),
            new CafeItem("unscathed_medal", "無傷の勲章", "\uD83C\uDF96\uFE0F",
                    "章を初めて制覇したときの獲得コインがさらに50%増加",
                    600_000L, 0, 0L, "chapter_no_hint", "chapter_medal_percent", 50),
            new CafeItem("one_sitting_bookmark", "一気読みのしおり", "\uD83D\uDCC3",
                    "問題を4問クリアするたび、その問題の獲得コインが必ず2倍",
                    600_000L, 0, 0L, "chapter_one_day", "task_beat", 2),
            new CafeItem("persistence_dripper", "粘りのドリッパー", "\uD83D\uDCA7",
                    "5回以上提出してクリアした問題の獲得コインが2倍",
                    12_000L, 0, 0L, "persistent_clear", "retry_double", 2),
            new CafeItem("food_truck", "移動販売トラック", "\uD83D\uDE9A",
                    "注文の集客が2店舗ぶん増える（店舗が少ないうちほど効く）",
                    900_000L, 0, 0L, "store_5", "store_bonus", 2),
            new CafeItem("clover_coaster", "四つ葉のコースター", "\uD83C\uDF40",
                    "ラッキー発動の確率が10%増える（ラッキーコインと合わせて22%）",
                    500_000L, 0, 0L, "quiz_total_50", "lucky_chance", 10));

    /** 達成型アイテムの解放条件。画面のカードにそのまま出す。 */
    private static final Map<String, String> ACHIEVEMENT_NOTES = Map.ofEntries(
            Map.entry("flawless_10", "ヒントなし・1回の提出で10問連続クリア"),
            Map.entry("same_day_15", "同じ日に15問クリア"),
            Map.entry("streak_7", "7日連続で学習"),
            Map.entry("quiz_streak_20", "確認クイズを20問連続で初回正解"),
            Map.entry("chapter_no_hint", "1つの章をヒントなしで全問クリア"),
            Map.entry("chapter_one_day", "1つの章を同じ日に全問クリア"),
            Map.entry("persistent_clear", "10回以上提出した問題をクリア"),
            Map.entry("store_5", "店舗を5店まで広げる"),
            Map.entry("quiz_total_50", "確認クイズに累計50問初回正解"));

    /**
     * アプリ画面を表示している間だけ動く自動営業設備。
     *
     * 率は「次の問題を初クリアしたときの売上」に対する1分あたりの割合。
     * 最上位でも5%/分、かつ次の★まで0.5問分が上限なので、問題を解く方が常に大きい。
     */
    private static final List<CafeAutomation> CAFE_AUTOMATION = List.of(
            new CafeAutomation("warming_pot", "保温ポット", "🫖",
                    "作り置きを少しずつ販売 · 学習1回分の0.5%/分", 2_500L, 1, 4, 50),
            new CafeAutomation("self_service", "セルフサービス台", "🥤",
                    "会計を待たずに販売 · 学習1回分の0.9%/分", 20_000L, 2, 22, 90),
            new CafeAutomation("order_kiosk", "注文キオスク", "🖥️",
                    "注文と決済を自動化 · 学習1回分の1.3%/分", 150_000L, 3, 57, 130),
            new CafeAutomation("auto_brew_line", "自動抽出ライン", "⚙️",
                    "抽出工程を自動連携 · 学習1回分の1.7%/分", 1_000_000L, 4, 101, 170),
            new CafeAutomation("unmanned_cafe", "自動会計システム", "💳",
                    "提供後の会計まで自動化 · 学習1回分の2.1%/分", 6_000_000L, 5, 144, 210),
            new CafeAutomation("serving_robot", "配膳ロボット", "🤖",
                    "客席への提供も自動化 · 学習1回分の2.5%/分", 30_000_000L, 6, 187, 250),
            new CafeAutomation("demand_ai", "AI需要予測", "📈",
                    "来店予測で作り置きを最適化 · 学習1回分の3%/分", 150_000_000L, 7, 230, 300),
            new CafeAutomation("smart_store_control", "スマート店舗管制", "🛰️",
                    "全設備を一括制御 · 学習1回分の3.5%/分", 750_000_000L, 8, 280, 350),
            new CafeAutomation("round_clock_cafe", "24時間無人店舗", "🌙",
                    "表示中の店舗運営を完全無人化 · 学習1回分の4%/分", 4_000_000_000L, 9, 330, 400),
            new CafeAutomation("autonomous_cafe", "完全自律カフェ", "🦾",
                    "注文から提供まで自律運転 · 学習1回分の4.4%/分", 20_000_000_000L, 10, 380, 440),
            new CafeAutomation("learning_grid", "学習グリッド管制", "🌐",
                    "世界の店舗を共同制御 · 学習1回分の4.7%/分", 100_000_000_000L, 11, 445, 470),
            new CafeAutomation("mastery_ai", "マスタリー運営AI", "🧠",
                    "全店舗の注文を最適化 · 学習1回分の5%/分", 400_000_000_000L, 12, 499, 500));

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    public ProgressStore(Path file) {
        this.file = file;
        load();
        // saveEventually() で溜まったぶんを定期的に書き出す。変更が無い回は何もしない
        saver.scheduleWithFixedDelay(this::flushNow,
                TRICKLE_SAVE_INTERVAL_SEC, TRICKLE_SAVE_INTERVAL_SEC, TimeUnit.SECONDS);
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
    public synchronized Object toClientJson(CafeLearningProgress learning) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("starCount", cleared.size());
        m.put("streak", streak());
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("cafe", cafeToClientJson(learning));
        return m;
    }

    private Object cafeToClientJson(CafeLearningProgress learning) {
        CafeLevel level = currentCafeLevel();
        CafeLevel nextLevel = level.level() < CAFE_LEVELS.size()
                ? CAFE_LEVELS.get(level.level())
                : null;
        long orderCups = cupsPerNetworkOrderWithUpgrades();
        long brandMultiplierBasisPoints = cafeBrandMultiplierBasisPoints(learning.masteredChapterTasks());
        boolean maximumNetwork = cafeStores >= MAX_CAFE_STORES;
        int progressStoreLimit = currentCafeStoreLimit();
        boolean canExpandNetwork = !maximumNetwork && cafeStores < progressStoreLimit;
        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("lifetimeCash", cafeLifetimeCash);
        cafe.put("taskRewardCount", cafeTaskRewardCount);
        cafe.put("cupPrice", CUP_PRICE);
        cafe.put("level", level.level());
        cafe.put("levelTitle", level.title());
        cafe.put("levelThreshold", level.threshold());
        cafe.put("nextLevelStars", nextLevel == null ? null : nextLevel.threshold());
        cafe.put("cupsPerOrder", level.cupsPerOrder());
        cafe.put("orderCups", orderCups);
        cafe.put("nextOrderCash", cafeCashForCups(orderCups, learning.masteredChapterTasks()));
        cafe.put("passiveCashPerMinute", cafePassiveCashPerMinute(learning.masteredChapterTasks()));
        long passiveCap = cafePassiveCashCap(learning.masteredChapterTasks());
        cafe.put("passiveCashCap", passiveCap);
        cafe.put("passiveCashRemaining", Math.max(0L, passiveCap - cafePassiveCashSinceTask));
        CafeAutomation activeAutomation = currentCafeAutomation();
        cafe.put("passiveRateBasisPoints", activeAutomation == null
                ? 0 : activeAutomation.rateBasisPointsPerMinute());
        cafe.put("bonusPercent", cafeBonusPercent());
        cafe.put("salesBonusPercent", cafeSalesBonusPercent());
        cafe.put("streakBonusPercent", cafeStreakBonusPercent());
        cafe.put("extraCups", cafeExtraCups());
        cafe.put("chapterBonusPercent", cafeChapterBonusPercent());
        cafe.put("quizTipPercent", cafeQuizTipPercent());
        cafe.put("clearedChapters", learning.clearedChapters());
        cafe.put("masteredChapterTasks", learning.masteredChapterTasks());
        cafe.put("brandMultiplierBasisPoints", brandMultiplierBasisPoints);
        cafe.put("storeCount", cafeStores);
        cafe.put("maxStores", MAX_CAFE_STORES);
        cafe.put("storeLimit", Math.max(cafeStores, progressStoreLimit));
        cafe.put("nextStoreUnlockStars", canExpandNetwork || maximumNetwork
                ? null : nextCafeStoreUnlockStars());
        int nextStoreGain = canExpandNetwork ? nextCafeStoreGain(progressStoreLimit) : 0;
        cafe.put("nextStoreGain", nextStoreGain);
        cafe.put("nextStoreCount", canExpandNetwork ? cafeStores + nextStoreGain : null);
        cafe.put("expansionCost", canExpandNetwork ? nextCafeExpansionCost() : null);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("ownedAutomation", new ArrayList<>(cafeAutomationUpgrades));
        cafe.put("ownedItems", new ArrayList<>(cafeItems));

        List<Object> upgrades = new ArrayList<>();
        for (CafeUpgrade u : CAFE_UPGRADES) {
            CafeUpgrade equipped = currentCafeUpgrade(u.effectType());
            int nextTier = equipped == null ? 1 : equipped.tier() + 1;
            long effectiveCost = cafeUpgradeCost(u);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.id());
            item.put("name", u.name());
            item.put("emoji", u.emoji());
            item.put("description", u.description());
            item.put("cost", effectiveCost);
            item.put("baseCost", u.cost());
            item.put("discounted", effectiveCost < u.cost());
            item.put("tier", u.tier());
            int requiredStars = equipmentRequiredStars(u);
            item.put("requiredStars", requiredStars);
            item.put("starReady", cleared.size() >= requiredStars);
            item.put("effectType", u.effectType());
            item.put("effectValue", u.effectValue());
            item.put("owned", cafeUpgrades.contains(u.id()));
            item.put("equipped", equipped != null && equipped.id().equals(u.id()));
            item.put("available", u.tier() == nextTier);
            upgrades.add(item);
        }
        cafe.put("upgrades", upgrades);

        List<Object> automation = new ArrayList<>();
        int nextAutomationTier = activeAutomation == null ? 1 : activeAutomation.tier() + 1;
        for (CafeAutomation item : CAFE_AUTOMATION) {
            long effectiveCost = cafeAutomationCost(item);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("name", item.name());
            value.put("emoji", item.emoji());
            value.put("description", item.description());
            value.put("cost", effectiveCost);
            value.put("baseCost", item.cost());
            value.put("discounted", effectiveCost < item.cost());
            value.put("tier", item.tier());
            value.put("requiredStars", item.requiredStars());
            value.put("starReady", cleared.size() >= item.requiredStars());
            value.put("rateBasisPointsPerMinute", item.rateBasisPointsPerMinute());
            value.put("owned", cafeAutomationUpgrades.contains(item.id()));
            value.put("equipped", activeAutomation != null && activeAutomation.id().equals(item.id()));
            value.put("available", item.tier() == nextAutomationTier);
            automation.add(value);
        }
        cafe.put("automation", automation);

        List<Object> items = new ArrayList<>();
        int unseenItemCount = 0;
        for (CafeItem item : CAFE_ITEMS) {
            boolean owned = cafeItems.contains(item.id());
            boolean discovered = owned || isCafeItemDiscovered(item);
            if (!discovered) {
                continue;
            }
            boolean unseen = !cafeSeenItems.contains(item.id());
            if (unseen) {
                unseenItemCount++;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("name", item.name());
            value.put("emoji", item.emoji());
            value.put("description", item.description());
            value.put("cost", item.cost());
            value.put("discovered", true);
            value.put("unseen", unseen);
            value.put("owned", owned);
            value.put("effectType", item.effectType());
            value.put("effectValue", item.effectValue());
            value.put("unlockNote", item.byAchievement()
                    ? ACHIEVEMENT_NOTES.getOrDefault(item.unlockAchievement(), "")
                    : "");
            items.add(value);
        }
        cafe.put("items", items);
        cafe.put("unseenItemCount", unseenItemCount);
        return cafe;
    }

    /**
     * 初クリアした注文の報酬。客単価・連続学習ボーナスは売上へ掛ける。
     *
     * {@code taskKey} は、ヒントを使ったか・何回で通ったかを見るアイテム
     * （一発仕上げのタンパー、粘りのドリッパー）のために受け取る。
     */
    public synchronized CafeAward rewardTask(CafeLearningProgress learning, String taskKey) {
        cafePassiveCashSinceTask = 0;
        resetCafePassiveClock();
        // テストケース数ではなく店舗の集客力で販売数を増やす。
        // 店舗ごとに同じ注文が入り、章クリアで育つブランド倍率を最後に掛ける。
        long cups = cupsPerNetworkOrderWithUpgrades();
        long cash = cafeCashForCups(cups, learning.masteredChapterTasks());
        return addCafeReward("task", cash, cups, taskKey);
    }

    /** 章を初めて制覇したときのまとまったボーナス。 */
    public synchronized CafeAward rewardChapter(
            String chapterId, CafeLearningProgress learning, int chapterTaskCount) {
        if (!rewardedChapters.add(chapterId)) {
            return CafeAward.NONE;
        }
        // 章内の通常報酬合計の25%を基準にする。2問章と12問章が同額になる偏りを避ける。
        long chapterOrderCups = saturatedMultiply(
                cupsPerNetworkOrderWithUpgrades(), Math.max(1, chapterTaskCount));
        long baseCups = ceilDivide(chapterOrderCups, 4L);
        long cups = applyPercent(baseCups, 100L + cafeChapterBonusPercent());
        long cash = cafeCashForCups(cups, learning.masteredChapterTasks());
        return addCafeReward("chapter", cash, cups);
    }

    /** クイズに初めて正解したときだけチップを付ける。 */
    public synchronized CafeAward rewardQuiz(
            String lessonId, int index, CafeLearningProgress learning) {
        String key = quizKey(lessonId, index);
        if (!rewardedQuizzes.add(key)) {
            return CafeAward.NONE;
        }
        // 初回正解の累計はここで増えるので、達成条件の見直しもここで行う
        // （recordQuiz 側だけに任せると、解放が1問ぶん遅れる）
        refreshCafeAchievements();
        // 現在の1問売上の2%を基準にする。難しい後半でもクイズの価値が薄れず、
        // クイズ接客設備を最大にしても通常の学習報酬を恒常的には超えない。
        long taskCash = cafeCashForCups(
                cupsPerNetworkOrderWithUpgrades(), learning.masteredChapterTasks());
        long baseTip = Math.max(saturatedMultiply(100L, cafeStores), applyPercent(taskCash, 2L));
        long cash = applyPercent(baseTip, 100L + cafeQuizTipPercent());
        return addCafeReward("quiz", cash, 0);
    }

    /** 設備を購入する。残高・直前ランク・重複購入をここで一括判定する。 */
    public synchronized PurchaseResult purchaseCafeUpgrade(String id) {
        CafeUpgrade upgrade = CAFE_UPGRADES.stream()
                .filter(u -> u.id().equals(id))
                .findFirst()
                .orElse(null);
        if (upgrade == null) {
            return new PurchaseResult(false, "その設備はありません", null, null);
        }
        if (cafeUpgrades.contains(id)) {
            return new PurchaseResult(false, "その設備は購入済みです", upgrade, null);
        }
        CafeUpgrade equipped = currentCafeUpgrade(upgrade.effectType());
        if (equipped != null && upgrade.tier() < equipped.tier()) {
            return new PurchaseResult(false,
                    "すでに上位設備「" + equipped.name() + "」を装備しています", upgrade, equipped);
        }
        int nextTier = equipped == null ? 1 : equipped.tier() + 1;
        if (upgrade.tier() != nextTier) {
            CafeUpgrade next = cafeUpgradeAt(upgrade.effectType(), nextTier);
            String nextName = next == null ? "現在の設備" : "「" + next.name() + "」";
            return new PurchaseResult(false,
                    "先に" + nextName + "へアップグレードしてください", upgrade, equipped);
        }
        int requiredStars = equipmentRequiredStars(upgrade);
        if (cleared.size() < requiredStars) {
            return new PurchaseResult(false,
                    "この設備ランクには★" + requiredStars + "が必要です", upgrade, equipped);
        }
        long cost = cafeUpgradeCost(upgrade);
        if (cafeCash < cost) {
            return new PurchaseResult(false, "コインが足りません", upgrade, equipped);
        }
        cafeCash -= cost;
        cafeUpgrades.add(id);
        resetCafePassiveClock();
        saveSoon();
        return new PurchaseResult(true, null, upgrade, equipped);
    }

    /** 自動営業設備を、必要な★数と直前ランクを満たしたときだけ購入する。 */
    public synchronized AutomationPurchaseResult purchaseCafeAutomation(String id) {
        CafeAutomation automation = CAFE_AUTOMATION.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
        if (automation == null) {
            return new AutomationPurchaseResult(false, "その自動営業設備はありません", null, null);
        }
        if (cafeAutomationUpgrades.contains(id)) {
            return new AutomationPurchaseResult(false, "その自動営業設備は購入済みです",
                    automation, null);
        }
        CafeAutomation equipped = currentCafeAutomation();
        int nextTier = equipped == null ? 1 : equipped.tier() + 1;
        if (automation.tier() != nextTier) {
            CafeAutomation next = cafeAutomationAt(nextTier);
            String nextName = next == null ? "現在の設備" : "「" + next.name() + "」";
            return new AutomationPurchaseResult(false,
                    "先に" + nextName + "へアップグレードしてください", automation, equipped);
        }
        if (cleared.size() < automation.requiredStars()) {
            return new AutomationPurchaseResult(false,
                    "この設備には★" + automation.requiredStars() + "が必要です", automation, equipped);
        }
        long cost = cafeAutomationCost(automation);
        if (cafeCash < cost) {
            return new AutomationPurchaseResult(false, "コインが足りません", automation, equipped);
        }
        cafeCash -= cost;
        cafeAutomationUpgrades.add(id);
        resetCafePassiveClock();
        saveSoon();
        return new AutomationPurchaseResult(true, null, automation, equipped);
    }

    /** アプリ画面を表示した。ここを起点にするため、画面外だった時間は売上にならない。 */
    public synchronized PassiveSalesResult startCafePassiveSales(
            String sessionId, int masteredChapterTasks) {
        cafePassiveSessionId = sessionId;
        cafePassiveLastTickNanos = System.nanoTime();
        cafePassiveRemainder = 0;
        return new PassiveSalesResult(0, cafePassiveCashPerMinute(masteredChapterTasks), true);
    }

    /** 表示中のアプリ画面からの定期連絡ぶんだけ、自動売上を加算する。 */
    public synchronized PassiveSalesResult collectCafePassiveSales(
            String sessionId, int masteredChapterTasks) {
        if (sessionId == null || !sessionId.equals(cafePassiveSessionId)) {
            return new PassiveSalesResult(0, cafePassiveCashPerMinute(masteredChapterTasks), false);
        }
        long now = System.nanoTime();
        long elapsedMillis = Math.max(0L, (now - cafePassiveLastTickNanos) / 1_000_000L);
        elapsedMillis = Math.min(elapsedMillis, MAX_PASSIVE_TICK_MILLIS);
        cafePassiveLastTickNanos = now;

        long ratePerMinute = cafePassiveCashPerMinute(masteredChapterTasks);
        long remaining = Math.max(0L,
                cafePassiveCashCap(masteredChapterTasks) - cafePassiveCashSinceTask);
        if (ratePerMinute <= 0 || elapsedMillis <= 0 || remaining <= 0) {
            return new PassiveSalesResult(0, ratePerMinute, true);
        }
        long numerator = saturatedAdd(cafePassiveRemainder,
                saturatedMultiply(ratePerMinute, elapsedMillis));
        long earned = Math.min(remaining, numerator / 60_000L);
        cafePassiveRemainder = numerator % 60_000L;
        if (earned > 0) {
            cafeCash = saturatedAdd(cafeCash, earned);
            cafeLifetimeCash = saturatedAdd(cafeLifetimeCash, earned);
            cafePassiveCashSinceTask = saturatedAdd(cafePassiveCashSinceTask, earned);
            // ここは2.5秒ごとに来る。毎回書くとファイル全体の書き直しが延々と続くので、
            // 定期便に任せる（★や購入と違い、失っても次のtickで取り戻せる額）
            saveEventually();
        }
        return new PassiveSalesResult(earned, ratePerMinute, true);
    }

    /** 画面を離れる直前までを精算して、自動営業セッションを閉じる。 */
    public synchronized PassiveSalesResult stopCafePassiveSales(
            String sessionId, int masteredChapterTasks) {
        PassiveSalesResult result = collectCafePassiveSales(sessionId, masteredChapterTasks);
        if (sessionId != null && sessionId.equals(cafePassiveSessionId)) {
            cafePassiveSessionId = null;
            cafePassiveLastTickNanos = 0;
            cafePassiveRemainder = 0;
        }
        return new PassiveSalesResult(result.cash(), result.cashPerMinute(), false);
    }

    /** 発見済みのスペシャルアイテムを購入する。アイテムは設備とは別枠で全て同時に所持できる。 */
    public synchronized ItemPurchaseResult purchaseCafeItem(String id) {
        CafeItem item = CAFE_ITEMS.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElse(null);
        if (item == null) {
            return new ItemPurchaseResult(false, "そのアイテムはありません", null);
        }
        if (cafeItems.contains(id)) {
            return new ItemPurchaseResult(false, "そのアイテムは所持済みです", item);
        }
        if (!isCafeItemDiscovered(item)) {
            return new ItemPurchaseResult(false, "このアイテムはまだ発見されていません", item);
        }
        if (cafeCash < item.cost()) {
            return new ItemPurchaseResult(false, "コインが足りません", item);
        }
        cafeCash -= item.cost();
        cafeItems.add(id);
        resetCafePassiveClock();
        saveSoon();
        return new ItemPurchaseResult(true, null, item);
    }

    /** 現在までに解放されたアイテムを確認済みにする。未解放アイテムの存在は記録しない。 */
    public synchronized void markCafeItemsSeen() {
        boolean changed = false;
        for (CafeItem item : CAFE_ITEMS) {
            if ((cafeItems.contains(item.id()) || isCafeItemDiscovered(item))
                    && cafeSeenItems.add(item.id())) {
                changed = true;
            }
        }
        if (changed) {
            saveSoon();
        }
    }

    /** 現在の約50%にあたる新店舗をまとめて開く。出店するほど一度の拡大量も増える。 */
    public synchronized ExpansionResult expandCafeNetwork() {
        if (cafeStores >= MAX_CAFE_STORES) {
            return new ExpansionResult(false, "店舗ネットワークは最大規模です",
                    cafeStores, 0, cafeStores, 0);
        }
        int storeLimit = currentCafeStoreLimit();
        if (cafeStores >= storeLimit) {
            Integer unlockStars = nextCafeStoreUnlockStars();
            String message = unlockStars == null
                    ? "店舗ネットワークは現在の上限です"
                    : "次の出店枠には★" + unlockStars + "が必要です";
            return new ExpansionResult(false, message, cafeStores, 0, cafeStores, 0);
        }
        long cost = nextCafeExpansionCost();
        if (cafeCash < cost) {
            return new ExpansionResult(false, "出店に必要なコインが足りません",
                    cafeStores, 0, cafeStores, cost);
        }
        int previousStores = cafeStores;
        int addedStores = nextCafeStoreGain(storeLimit);
        cafeCash -= cost;
        cafeStores += addedStores;
        refreshCafeAchievements();
        resetCafePassiveClock();
        saveSoon();
        return new ExpansionResult(true, null, previousStores, addedStores, cafeStores, cost);
    }

    private CafeAward addCafeReward(String trigger, long cash, long cups) {
        return addCafeReward(trigger, cash, cups, null);
    }

    private CafeAward addCafeReward(String trigger, long cash, long cups, String taskKey) {
        cafeRewardSequence = saturatedAdd(cafeRewardSequence, 1L);
        if (trigger.equals("task")) {
            cafeTaskRewardCount = saturatedAdd(cafeTaskRewardCount, 1L);
        }
        long rewardedCash = cash;
        List<String> itemEvents = new ArrayList<>();

        CafeItem luckyCoin = cafeItemByEffect("lucky_double");
        CafeItem clover = cafeItemByEffect("lucky_chance");
        int luckyPercent = (isCafeItemOwned(luckyCoin) ? LUCKY_COIN_CHANCE_PERCENT : 0)
                + (isCafeItemOwned(clover) ? clover.effectValue() : 0);
        if (luckyPercent > 0 && isLuckyHit(cafeRewardSequence, luckyPercent)) {
            CafeItem source = isCafeItemOwned(luckyCoin) ? luckyCoin : clover;
            rewardedCash = saturatedMultiply(rewardedCash, 2L);
            itemEvents.add(source.emoji() + " " + source.name() + "発動！ コイン×2");
        }

        CafeItem comboBook = cafeItemByEffect("task_combo");
        if (trigger.equals("task") && isCafeItemOwned(comboBook)
                && cafeTaskRewardCount % TASK_COMBO_INTERVAL == 0) {
            rewardedCash = saturatedMultiply(rewardedCash, comboBook.effectValue());
            itemEvents.add(comboBook.emoji() + " " + comboBook.name()
                    + "完成！ 5問目ボーナス×" + comboBook.effectValue());
        }

        CafeItem rhythmRecipe = cafeItemByEffect("task_rhythm");
        if (trigger.equals("task") && isCafeItemOwned(rhythmRecipe)
                && cafeTaskRewardCount % 7L == 0) {
            rewardedCash = saturatedMultiply(rewardedCash, rhythmRecipe.effectValue());
            itemEvents.add(rhythmRecipe.emoji() + " " + rhythmRecipe.name()
                    + "完成！ 7問目ボーナス×" + rhythmRecipe.effectValue());
        }

        CafeItem beatBookmark = cafeItemByEffect("task_beat");
        if (trigger.equals("task") && isCafeItemOwned(beatBookmark)
                && cafeTaskRewardCount % TASK_BEAT_INTERVAL == 0) {
            rewardedCash = saturatedMultiply(rewardedCash, beatBookmark.effectValue());
            itemEvents.add(beatBookmark.emoji() + " " + beatBookmark.name()
                    + "完成！ " + TASK_BEAT_INTERVAL + "問目ボーナス×" + beatBookmark.effectValue());
        }

        Cleared clearedTask = taskKey == null ? null : cleared.get(taskKey);

        CafeItem tamper = cafeItemByEffect("first_try_percent");
        if (trigger.equals("task") && isCafeItemOwned(tamper) && clearedTask != null
                && clearedTask.hintsUsed() == 0 && clearedTask.attempts() <= 1) {
            rewardedCash = applyPercent(rewardedCash, 100L + tamper.effectValue());
            itemEvents.add(tamper.emoji() + " " + tamper.name()
                    + "で一発クリア+" + tamper.effectValue() + "%");
        }

        CafeItem dripper = cafeItemByEffect("retry_double");
        if (trigger.equals("task") && isCafeItemOwned(dripper) && clearedTask != null
                && clearedTask.attempts() >= RETRY_BONUS_ATTEMPTS) {
            rewardedCash = saturatedMultiply(rewardedCash, dripper.effectValue());
            itemEvents.add(dripper.emoji() + " " + dripper.name()
                    + "で粘りボーナス×" + dripper.effectValue());
        }

        CafeItem prepPot = cafeItemByEffect("daily_ramp_percent");
        if (trigger.equals("task") && isCafeItemOwned(prepPot)) {
            int ramp = Math.min(prepPot.effectValue(), todayClearCount());
            if (ramp > 0) {
                rewardedCash = applyPercent(rewardedCash, 100L + ramp);
                itemEvents.add(prepPot.emoji() + " " + prepPot.name()
                        + "で今日の仕込み+" + ramp + "%");
            }
        }

        CafeItem quizMegaphone = cafeItemByEffect("quiz_multiplier");
        if (trigger.equals("quiz") && isCafeItemOwned(quizMegaphone)) {
            rewardedCash = saturatedMultiply(rewardedCash, quizMegaphone.effectValue());
            itemEvents.add(quizMegaphone.emoji() + " " + quizMegaphone.name()
                    + "で正解チップ×" + quizMegaphone.effectValue());
        }

        CafeItem quizFestival = cafeItemByEffect("quiz_extra_multiplier");
        if (trigger.equals("quiz") && isCafeItemOwned(quizFestival)) {
            rewardedCash = saturatedMultiply(rewardedCash, quizFestival.effectValue());
            itemEvents.add(quizFestival.emoji() + " " + quizFestival.name()
                    + "で正解チップ×" + quizFestival.effectValue());
        }

        CafeItem quizBell = cafeItemByEffect("quiz_bell_multiplier");
        if (trigger.equals("quiz") && isCafeItemOwned(quizBell)) {
            rewardedCash = saturatedMultiply(rewardedCash, quizBell.effectValue());
            itemEvents.add(quizBell.emoji() + " " + quizBell.name()
                    + "で正解チップ×" + quizBell.effectValue());
        }

        CafeItem medal = cafeItemByEffect("chapter_medal_percent");
        if (trigger.equals("chapter") && isCafeItemOwned(medal)) {
            rewardedCash = applyPercent(rewardedCash, 100L + medal.effectValue());
            itemEvents.add(medal.emoji() + " " + medal.name()
                    + "で章報酬+" + medal.effectValue() + "%");
        }

        CafeItem chapterCake = cafeItemByEffect("chapter_multiplier");
        if (trigger.equals("chapter") && isCafeItemOwned(chapterCake)) {
            rewardedCash = saturatedMultiply(rewardedCash, chapterCake.effectValue());
            itemEvents.add(chapterCake.emoji() + " " + chapterCake.name()
                    + "で章制覇ボーナス×" + chapterCake.effectValue());
        }

        CafeItem masteryArchive = cafeItemByEffect("chapter_extra_percent");
        if (trigger.equals("chapter") && isCafeItemOwned(masteryArchive)) {
            rewardedCash = applyPercent(rewardedCash, 100L + masteryArchive.effectValue());
            itemEvents.add(masteryArchive.emoji() + " " + masteryArchive.name()
                    + "で章報酬+" + masteryArchive.effectValue() + "%");
        }

        CafeItem lifelongTrophy = cafeItemByEffect("mastery_bonus");
        if (isCafeItemOwned(lifelongTrophy)) {
            rewardedCash = applyPercent(rewardedCash, 100L + lifelongTrophy.effectValue());
            itemEvents.add(lifelongTrophy.emoji() + " " + lifelongTrophy.name()
                    + "で学習報酬+" + lifelongTrophy.effectValue() + "%");
        }
        cafeCash = saturatedAdd(cafeCash, rewardedCash);
        cafeLifetimeCash = saturatedAdd(cafeLifetimeCash, rewardedCash);
        cafeCups = saturatedAdd(cafeCups, cups);
        saveSoon();
        return new CafeAward(rewardedCash, cups, List.copyOf(itemEvents));
    }

    private boolean isCafeItemDiscovered(CafeItem item) {
        if (item.byAchievement()) {
            return cafeAchievements.contains(item.unlockAchievement());
        }
        return cleared.size() >= item.unlockStars()
                && cafeLifetimeCash >= item.unlockLifetimeCash();
    }

    /**
     * 達成条件を見直して、満たしたものを記録する。
     *
     * いちど達成したら外さない。連続記録のように後で崩れるものもあるため、
     * 「今の状態」ではなく「達成したことがあるか」を残す。
     */
    private boolean refreshCafeAchievements() {
        int flawlessRun = 0;
        int bestFlawlessRun = 0;
        boolean persistent = false;
        Map<String, Integer> clearsPerDay = new LinkedHashMap<>();
        for (Cleared c : cleared.values()) {
            if (c.hintsUsed() == 0 && c.attempts() <= 1) {
                flawlessRun++;
                bestFlawlessRun = Math.max(bestFlawlessRun, flawlessRun);
            } else {
                flawlessRun = 0;
            }
            if (c.attempts() >= 10) {
                persistent = true;
            }
            clearsPerDay.merge(c.clearedAt(), 1, Integer::sum);
        }
        int busiestDay = 0;
        for (int count : clearsPerDay.values()) {
            busiestDay = Math.max(busiestDay, count);
        }
        boolean changed = award("flawless_10", bestFlawlessRun >= 10);
        changed |= award("same_day_15", busiestDay >= 15);
        changed |= award("streak_7", longestClearStreak() >= 7);
        changed |= award("quiz_streak_20", cafeQuizFirstStreak >= 20);
        changed |= award("quiz_total_50", rewardedQuizzes.size() >= 50);
        changed |= award("persistent_clear", persistent);
        changed |= award("store_5", cafeStores >= 5);
        return changed;
    }

    /**
     * 章を全問クリアしたときだけ分かる達成条件を記録する。
     *
     * 章に属する問題キーは教材側しか知らないので、呼び出し側から渡してもらう。
     * 何度呼ばれても記録が増えるだけなので、報酬のような重複防止は要らない。
     */
    public synchronized void noteChapterAchievements(List<String> chapterTaskKeys) {
        if (chapterTaskKeys.isEmpty()) {
            return;
        }
        boolean hintFree = true;
        boolean sameDay = true;
        String firstDay = null;
        for (String key : chapterTaskKeys) {
            Cleared c = cleared.get(key);
            if (c == null) {
                return;
            }
            if (c.hintsUsed() > 0) {
                hintFree = false;
            }
            if (firstDay == null) {
                firstDay = c.clearedAt();
            } else if (!firstDay.equals(c.clearedAt())) {
                sameDay = false;
            }
        }
        boolean changed = award("chapter_no_hint", hintFree);
        changed |= award("chapter_one_day", sameDay);
        if (changed) {
            saveSoon();
        }
    }

    private boolean award(String achievement, boolean reached) {
        return reached && cafeAchievements.add(achievement);
    }

    /** これまでで最も長く続いた連続学習日数。今の連続が途切れていても残る。 */
    private int longestClearStreak() {
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (String day : clearDates) {
            LocalDate date;
            try {
                date = LocalDate.parse(day);
            } catch (RuntimeException e) {
                continue;
            }
            run = (previous != null && previous.plusDays(1).equals(date)) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = date;
        }
        return best;
    }

    /** 今日クリアした問題数。仕込み用の大鍋が積み上げる割合に使う。 */
    private int todayClearCount() {
        String today = LocalDate.now().toString();
        int count = 0;
        for (Cleared c : cleared.values()) {
            if (today.equals(c.clearedAt())) {
                count++;
            }
        }
        return count;
    }

    /** 保存された報酬回数から疑似乱数を作るため、再起動しても同じ報酬を引き直せない。 */
    private static boolean isLuckyHit(long sequence, int chancePercent) {
        long value = sequence ^ ((long) "lucky_coin".hashCode() << 32);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return Long.remainderUnsigned(value, 100L) < chancePercent;
    }

    private boolean isCafeItemOwned(CafeItem item) {
        return item != null && cafeItems.contains(item.id());
    }

    private static CafeItem cafeItemByEffect(String effectType) {
        return CAFE_ITEMS.stream()
                .filter(item -> item.effectType().equals(effectType))
                .findFirst()
                .orElse(null);
    }

    private int cafeBonusPercent() {
        return cafeSalesBonusPercent() + cafeStreakBonusPercent();
    }

    private int cafeSalesBonusPercent() {
        return cafeEffectTotal("sales");
    }

    /** 連続日数による注文売上アップ。長期離脱で差が開きすぎないよう7日を上限にする。 */
    private int cafeStreakBonusPercent() {
        CafeItem comebackTicket = cafeItemByEffect("streak_floor");
        int effectiveStreak = streak();
        if (isCafeItemOwned(comebackTicket)) {
            effectiveStreak = Math.max(effectiveStreak, comebackTicket.effectValue());
        }
        CafeItem calendar = cafeItemByEffect("streak_cap");
        int cap = isCafeItemOwned(calendar)
                ? Math.max(STREAK_BONUS_CAP_DAYS, calendar.effectValue())
                : STREAK_BONUS_CAP_DAYS;
        return cafeEffectTotal("streak") * Math.min(effectiveStreak, cap);
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
        CafeUpgrade equipped = currentCafeUpgrade(effectType);
        return equipped == null ? 0 : equipped.effectValue();
    }

    private CafeUpgrade currentCafeUpgrade(String effectType) {
        CafeUpgrade current = null;
        for (CafeUpgrade upgrade : CAFE_UPGRADES) {
            if (cafeUpgrades.contains(upgrade.id())
                    && upgrade.effectType().equals(effectType)
                    && (current == null || upgrade.tier() > current.tier())) {
                current = upgrade;
            }
        }
        return current;
    }

    private static CafeUpgrade cafeUpgradeAt(String effectType, int tier) {
        for (CafeUpgrade upgrade : CAFE_UPGRADES) {
            if (upgrade.effectType().equals(effectType) && upgrade.tier() == tier) {
                return upgrade;
            }
        }
        return null;
    }

    private long cupsPerOrderWithUpgrades() {
        return currentCafeLevel().cupsPerOrder() + cafeExtraCups();
    }

    private long cupsPerNetworkOrderWithUpgrades() {
        CafeItem truck = cafeItemByEffect("store_bonus");
        long stores = isCafeItemOwned(truck) ? cafeStores + truck.effectValue() : cafeStores;
        return saturatedMultiply(cupsPerOrderWithUpgrades(), stores);
    }

    private long cafeCashForCups(long cups, int masteredChapterTasks) {
        long baseCash = saturatedMultiply(cups, CUP_PRICE);
        long cashWithEquipment = applyPercent(baseCash, 100L + cafeBonusPercent());
        return applyBasisPoints(cashWithEquipment,
                cafeBrandMultiplierBasisPoints(masteredChapterTasks));
    }

    /** 完成した章に含まれる問題数で加算し、短い章だけを先取りする攻略を防ぐ。 */
    private long cafeBrandMultiplierBasisPoints(int masteredChapterTasks) {
        long growth = saturatedMultiply(Math.max(0, masteredChapterTasks),
                BRAND_GROWTH_BASIS_POINTS_PER_TASK);
        long basisPoints = saturatedAdd(10_000L, growth);
        CafeItem charter = cafeItemByEffect("brand_bonus");
        if (isCafeItemOwned(charter)) {
            basisPoints = saturatedAdd(basisPoints, charter.effectValue());
        }
        return basisPoints;
    }

    private static int equipmentRequiredStars(CafeUpgrade upgrade) {
        int index = Math.max(1, Math.min(upgrade.tier(), EQUIPMENT_REQUIRED_STARS.length - 1));
        int trackOffset = switch (upgrade.effectType()) {
            case "cups" -> 2;
            case "chapter" -> 4;
            case "tips" -> 6;
            case "streak" -> 8;
            default -> 0;
        };
        return EQUIPMENT_REQUIRED_STARS[index] + trackOffset;
    }

    private int currentCafeStoreLimit() {
        int limit = 1;
        int stars = cleared.size();
        for (int i = 0; i < STORE_UNLOCK_STARS.length; i++) {
            if (stars >= STORE_UNLOCK_STARS[i]) {
                limit = STORE_LIMITS[i];
            }
        }
        return limit;
    }

    private Integer nextCafeStoreUnlockStars() {
        for (int i = 0; i < STORE_LIMITS.length; i++) {
            if (STORE_LIMITS[i] > cafeStores && cleared.size() < STORE_UNLOCK_STARS[i]) {
                return STORE_UNLOCK_STARS[i];
            }
        }
        return null;
    }

    private int nextCafeStoreGain(int storeLimit) {
        int remaining = Math.min(MAX_CAFE_STORES, storeLimit) - cafeStores;
        int growth = Math.max(1, (cafeStores + 1) / 2);
        return Math.min(remaining, growth);
    }

    /**
     * 序盤は規模の二乗、5店舗以降は三乗で上がる出店費。
     * 後半の大きな収入にも見合う、長期のコイン消費先にする。
     */
    private long nextCafeExpansionCost() {
        long square = saturatedMultiply(cafeStores, cafeStores);
        long quadraticCost = saturatedMultiply(FIRST_EXPANSION_COST, square);
        long cubicCost = saturatedMultiply(EXPANSION_CUBIC_COST, saturatedMultiply(square, cafeStores));
        long baseCost = Math.max(quadraticCost, cubicCost);
        CafeItem map = cafeItemByEffect("expansion_discount");
        return isCafeItemOwned(map) ? discountedCost(baseCost, map.effectValue()) : baseCost;
    }

    private long cafeUpgradeCost(CafeUpgrade upgrade) {
        CafeItem toolbox = cafeItemByEffect("equipment_discount");
        return isCafeItemOwned(toolbox)
                ? discountedCost(upgrade.cost(), toolbox.effectValue())
                : upgrade.cost();
    }

    private long cafeAutomationCost(CafeAutomation automation) {
        CafeItem toolbox = cafeItemByEffect("equipment_discount");
        return isCafeItemOwned(toolbox)
                ? discountedCost(automation.cost(), toolbox.effectValue())
                : automation.cost();
    }

    private CafeAutomation currentCafeAutomation() {
        CafeAutomation current = null;
        for (CafeAutomation automation : CAFE_AUTOMATION) {
            if (cafeAutomationUpgrades.contains(automation.id())
                    && (current == null || automation.tier() > current.tier())) {
                current = automation;
            }
        }
        return current;
    }

    private static CafeAutomation cafeAutomationAt(int tier) {
        for (CafeAutomation automation : CAFE_AUTOMATION) {
            if (automation.tier() == tier) {
                return automation;
            }
        }
        return null;
    }

    /** 自動売上は現在の1問クリア売上の最大5%/分。 */
    private long cafePassiveCashPerMinute(int masteredChapterTasks) {
        CafeAutomation automation = currentCafeAutomation();
        if (automation == null) {
            return 0;
        }
        long taskCash = cafeCashForCups(
                cupsPerNetworkOrderWithUpgrades(), masteredChapterTasks);
        return Math.max(1L, applyBasisPoints(taskCash, automation.rateBasisPointsPerMinute()));
    }

    private long cafePassiveCashCap(int masteredChapterTasks) {
        long taskCash = cafeCashForCups(
                cupsPerNetworkOrderWithUpgrades(), masteredChapterTasks);
        return applyBasisPoints(taskCash, PASSIVE_CASH_CAP_BASIS_POINTS);
    }

    /** 投資直前の経過時間へ、購入後の高いレートをさかのぼって適用しない。 */
    private void resetCafePassiveClock() {
        if (cafePassiveSessionId != null) {
            cafePassiveLastTickNanos = System.nanoTime();
            cafePassiveRemainder = 0;
        }
    }

    private static long discountedCost(long cost, int discountPercent) {
        return Math.max(1L, applyPercent(cost, 100L - discountPercent));
    }

    private static long applyPercent(long value, long percent) {
        return saturatedMultiply(value, percent) / 100L;
    }

    private static long applyBasisPoints(long value, long basisPoints) {
        return saturatedMultiply(value, basisPoints) / 10_000L;
    }

    private static long ceilDivide(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return saturatedAdd(value, divisor - 1L) / divisor;
    }

    private static long saturatedMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private CafeLevel currentCafeLevel() {
        CafeLevel current = CAFE_LEVELS.get(0);
        for (CafeLevel level : CAFE_LEVELS) {
            if (cleared.size() >= level.threshold()) {
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
        saveSoon();
    }

    public synchronized int recordAttempt(String taskKey) {
        int n = attempts.merge(taskKey, 1, Integer::sum);
        saveSoon();
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
        saveSoon();
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
        refreshCafeAchievements();
        saveSoon();
        return isNew;
    }

    /** ヒントを1つ開示したことを記録し、開示済み総数を返す。 */
    public synchronized int revealHint(String taskKey, int index) {
        int current = hintsRevealed.getOrDefault(taskKey, 0);
        int next = Math.max(current, index + 1);
        hintsRevealed.put(taskKey, next);
        saveSoon();
        return next;
    }

    /**
     * クイズの回答を記録する（答え直したら上書きする）。
     *
     * 連続正解は「そのクイズに初めて答えたとき」だけ数える。答え直しで
     * 連続記録を作り直せないようにするため。
     */
    public synchronized void recordQuiz(String lessonId, int index, int choice, boolean correct) {
        String key = quizKey(lessonId, index);
        if (!quizChoices.containsKey(key)) {
            cafeQuizFirstStreak = correct ? cafeQuizFirstStreak + 1 : 0;
        }
        quizChoices.put(key, choice);
        refreshCafeAchievements();
        saveSoon();
    }

    /** 進捗を全て消す。 */
    public synchronized void resetAll() {
        clearAllState();
        saveSoon();
    }

    /**
     * 保持している状態を全て初期値へ戻す。
     *
     * {@link #resetAll()}（利用者が消したとき）と {@link #load()} の復旧処理
     * （進捗ファイルが壊れていたとき）の両方から呼ぶ。
     *
     * <p>この2箇所は以前それぞれが同じ並びを書き写していて、片方だけに
     * フィールドが足され、{@code cafeAchievements} と {@code cafeQuizFirstStreak} の
     * 消し忘れが生まれていた。フィールドを増やしたときに片方だけ直す事故を防ぐため、
     * 消す場所はこの1つに寄せる。</p>
     */
    private void clearAllState() {
        cleared.clear();
        codes.clear();
        hintsRevealed.clear();
        attempts.clear();
        bestPassed.clear();
        quizChoices.clear();
        clearDates.clear();
        cafeCash = 0;
        cafeCups = 0;
        cafeLifetimeCash = 0;
        cafeRewardSequence = 0;
        cafeTaskRewardCount = 0;
        cafePassiveCashSinceTask = 0;
        cafeStores = 1;
        cafeUpgrades.clear();
        cafeAutomationUpgrades.clear();
        cafeItems.clear();
        cafeSeenItems.clear();
        cafeAchievements.clear();
        cafeQuizFirstStreak = 0;
        rewardedQuizzes.clear();
        rewardedChapters.clear();
        cafePassiveSessionId = null;
        cafePassiveLastTickNanos = 0;
        cafePassiveRemainder = 0;
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
                cafeLifetimeCash = cafe.containsKey("lifetimeCash")
                        ? longOf(cafe, "lifetimeCash", cafeCash)
                        : Math.max(cafeCash, saturatedMultiply(cafeCups, CUP_PRICE));
                cafeRewardSequence = longOf(cafe, "rewardSequence", 0);
                cafeTaskRewardCount = longOf(cafe, "taskRewardCount", cleared.size());
                cafePassiveCashSinceTask = longOf(cafe, "passiveCashSinceTask", 0);
                cafeStores = Math.min(MAX_CAFE_STORES,
                        Math.max(1, MiniJson.intOf(cafe, "storeCount", 1)));
                int economyVersion = MiniJson.intOf(cafe, "economyVersion", 1);
                if (economyVersion < 2) {
                    // 初版は1杯ほぼ10円だったため、平均500円の新レートへ換算する。
                    cafeCash *= 50L;
                }
                for (Object o : MiniJson.list(cafe, "ownedUpgrades")) {
                    if (o instanceof String s && isKnownUpgrade(s)) {
                        cafeUpgrades.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "ownedAutomation")) {
                    if (o instanceof String s && isKnownAutomation(s)) {
                        cafeAutomationUpgrades.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "ownedItems")) {
                    if (o instanceof String s && isKnownItem(s)) {
                        cafeItems.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "seenItems")) {
                    if (o instanceof String s && isKnownItem(s)) {
                        cafeSeenItems.add(s);
                    }
                }
                for (Object o : MiniJson.list(cafe, "achievements")) {
                    if (o instanceof String s && ACHIEVEMENT_NOTES.containsKey(s)) {
                        cafeAchievements.add(s);
                    }
                }
                cafeQuizFirstStreak = MiniJson.intOf(cafe, "quizFirstStreak", 0);
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
                cafeLifetimeCash = cafeCash;
                cafeTaskRewardCount = cleared.size();
            }
            // すでに条件を満たしている人（連続学習や粘った問題の履歴がある人）へ、
            // 起動した時点でアイテムを解放する。
            refreshCafeAchievements();
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
                // 退避に失敗しても、以降の書き出しで上書きされる
            }
            // 途中まで読めていた分が残らないよう、全ての状態を初期値へ戻す。
            // （例外は最後の refreshCafeAchievements() でも起き得るので、
            //   達成条件や連続正解数まで消える必要がある）
            clearAllState();
        }
    }

    /**
     * 変更を記録し、少し待ってから書き出すよう予約する。
     *
     * 状態を変える synchronized メソッドの最後で呼ぶ（{@code dirty} と
     * {@code saveScheduled} は {@code this} の保護下で触る前提）。
     * ここから {@link #flushNow()} を直接呼んではいけない ―
     * {@code this} を持ったまま {@code writeLock} を取りに行くことになり、
     * 錠の順序が逆になる。
     */
    private void saveSoon() {
        dirty = true;
        if (!saveScheduled) {
            saveScheduled = true;
            saver.schedule(this::flushFromTimer, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 変更を記録するが、書き出しは定期便に任せる。
     *
     * 自動売上のように「短い間隔で何度も起き、失っても次の機会に作り直せる」変更用。
     * {@link #TRICKLE_SAVE_INTERVAL_SEC} ごとの書き出しか、次に
     * {@link #saveSoon()} が呼ばれたときに、まとめてディスクへ載る。
     */
    private void saveEventually() {
        dirty = true;
    }

    private void flushFromTimer() {
        synchronized (this) {
            // 先に下ろす。書き出している間に起きた変更を取りこぼさない
            saveScheduled = false;
        }
        flushNow();
    }

    /**
     * 溜まっている変更を今すぐ書き出す。変更が無ければ何もしない。
     *
     * 一時ファイルへ書いてから置き換えるので、書き込み中に落ちても
     * 進捗ファイルが半端な状態にはならない。
     *
     * <p>ディスクを待つのは {@code this} の外（{@code writeLock} の中）で行う。
     * 進捗ファイルが大きくなっても、書き込みのあいだ他のリクエストを止めない。
     * 状態の写し取りも {@code writeLock} の中でやるので、写した順と書いた順が
     * 入れ替わって古い内容を残すことはない。</p>
     */
    public void flushNow() {
        synchronized (writeLock) {
            String json;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                dirty = false;
                json = MiniJson.write(toJsonRaw());
            }
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                replace(tmp, file);
            } catch (IOException e) {
                System.err.println("進捗を保存できませんでした: " + e.getMessage());
                synchronized (this) {
                    dirty = true;   // 次の機会に書き直す
                }
            }
        }
    }

    /**
     * 一時ファイルを本体へ差し替える。
     *
     * 差し替えの途中で落ちても壊れないように、まず不可分な移動を試す。
     * 対応していないファイルシステムでは通常の上書きに落とす。
     */
    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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
        cafe.put("lifetimeCash", cafeLifetimeCash);
        cafe.put("rewardSequence", cafeRewardSequence);
        cafe.put("taskRewardCount", cafeTaskRewardCount);
        cafe.put("passiveCashSinceTask", cafePassiveCashSinceTask);
        cafe.put("storeCount", cafeStores);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("ownedAutomation", new ArrayList<>(cafeAutomationUpgrades));
        cafe.put("ownedItems", new ArrayList<>(cafeItems));
        cafe.put("seenItems", new ArrayList<>(cafeSeenItems));
        cafe.put("achievements", new ArrayList<>(cafeAchievements));
        cafe.put("quizFirstStreak", cafeQuizFirstStreak);
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

    private static boolean isKnownItem(String id) {
        return CAFE_ITEMS.stream().anyMatch(item -> item.id().equals(id));
    }

    private static boolean isKnownAutomation(String id) {
        return CAFE_AUTOMATION.stream().anyMatch(item -> item.id().equals(id));
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
