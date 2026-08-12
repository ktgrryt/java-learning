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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 学習の進捗を progress.json に保存する。
 *
 * 保持するもの:
 *  - 初回オンボーディングを完了したか
 *  - クリア済みの問題（クリア日、使ったヒント数、提出回数）
 *  - 問題ごとに最後に書いたコード（再訪時に復元する）
 *  - 確認クイズで選んだ選択肢（正解かどうかは保存せず、出題側と突き合わせて毎回求める）
 *  - 何か1問クリアした日付の集合（連続学習日数の計算に使う）
 *  - 問題ごとの苦手度とブックマーク（復習モードの出題順に使う）
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

    // 21: ラッキーコインの解放を、★・累計売上条件から「問題正解ごとに1%抽選」へ変更した。
    //     574問すべて外れても投資率45%以内になるよう、終盤改装の基準額を450億へ下げた。
    // 20: ラッキーコインを「頻繁な小当たり」から「5%の大当たり」へ変更し、価格を77,777にした。
    //     期待売上が下がるぶん、全購入時の投資率を範囲内へ戻すため終盤改装の基準額も下げた。
    private static final int CAFE_ECONOMY_VERSION = 21;
    private static final int CUP_PRICE = 500;
    private static final int MAX_CAFE_STORES = 512;
    private static final long FIRST_EXPANSION_COST = 2_500L;
    /**
     * 5店舗以降の出店費（規模の三乗に掛ける係数）。終盤の主なコイン消費先。
     *
     * <p><b>問題数を増やしたら {@code tools/simulate-cafe.sh} を通すこと。</b>
     * 生涯売上はブランド倍率（下の定数）が設備効果へ掛かるぶん、問題数に対して
     * 加速して伸びる。一方で購入費の合計は問題数と無関係なので、教材を増やすだけで
     * 投資率（購入費 ÷ 生涯売上）が下がり、目標の25〜45%を割る。
     * 現在は20問ごとの任意改装が追加分を吸収する。必須の店舗網の進行そのものに
     * 不具合があるときだけ、この係数を調整する。</p>
     *
     * <p>実測: 全509問で8,500だと22.9%、全516問で15,000だと27.7%、
     * 全532問で25,000だと25.92%、全547問で38,000だと25.12%だった。
     * 全574問では38,000だと18.26%まで落ち、57,000へ上げて25.35%に戻した。</p>
     */
    private static final long EXPANSION_CUBIC_COST = 57_000L;
    /** 完成した章の問題1問あたりのブランド成長。全574問で約x10.76になる。 */
    private static final int BRAND_GROWTH_BASIS_POINTS_PER_TASK = 170;
    /**
     * 復習で再正解した問題1問あたりのブランド成長。
     *
     * <p>復習にコインは払わない（クリア済みの問題は何度でも解き直せるので、
     * 1回ごとに支払うと無限に稼げてしまう）。代わりに、ここで倍率だけを育てる。
     * 対象は {@code cafeMasteryTasks} ―「復習で正解した重複しない問題」の集合なので、
     * 1問につき1回しか数えない。上限は問題数で構造的に決まる。</p>
     *
     * <p>復習ノートを持つと4倍になるため、1問あたりの上限は 40 * 4 = 160。
     * <b>初回クリアの170を超えないこと</b>が不変条件で、
     * {@code tools/simulate-cafe.sh} がここを検査する。順序を崩すと
     * 「新しい問題を解くより復習した方が儲かる」状態になる。</p>
     */
    private static final int REVIEW_BRAND_GROWTH_BASIS_POINTS_PER_TASK = 40;
    /**
     * 復習で戻せる自動売上の枠（問題数ぶん）の上限。
     *
     * 枠を戻さないままアプリを閉じて溜め込めないようにする。回収そのものは
     * レート（最上位でも5%/分）で律速されるので、1問分でも回収には20分かかる。
     */
    private static final int MAX_REVIEW_PASSIVE_CREDITS = 5;
    private static final int TASK_COMBO_INTERVAL = 5;
    /**
     * 取得の重い2アイテムの条件。
     *
     * 12種のうちこの2つだけは、学習量ではなく「やり込み」で解放する。
     * 復習ノートは全問の3分の1以上を復習したとき、生涯学習トロフィーは
     * ヒントなし・一発で25問続けたときに初めて現れる。
     */
    private static final int REVIEW_MASTERY_ITEM_TASKS = 200;
    private static final int FLAWLESS_ITEM_RUN = 25;
    /** 粘りのドリッパーが「粘った」とみなす提出回数。 */
    private static final int RETRY_BONUS_ATTEMPTS = 5;
    /** 粘りのドリッパーそのものが解放される提出回数（1問への累計）。 */
    private static final int RETRY_ACHIEVEMENT_ATTEMPTS = 10;
    /** 初回・復習を問わず、問題へ正解したときにラッキーコインを引く確率。 */
    private static final int LUCKY_COIN_UNLOCK_CHANCE_PERCENT = 1;
    /** 「今日の1杯目」に数える連続日数の既定の上限。皆勤の日めくりだけがこれを広げる。 */
    private static final int STREAK_BONUS_CAP_DAYS = 7;
    /**
     * 自動売上は、次に★を取るまで現在の問題報酬5問分まで。
     * 最上位設備でも上限まで100分かかり、オフライン中は増えない。
     */
    private static final int PASSIVE_CASH_CAP_BASIS_POINTS = 50_000;
    /** 終盤の任意投資は★520から20問ごとに1段階ずつ解放する。 */
    private static final int ENDGAME_INVESTMENT_START_STARS = 500;
    private static final int ENDGAME_INVESTMENT_STAR_INTERVAL = 20;
    /** 収益効果のない任意投資。1段階ごとに価格を2倍にし、追加章のコイン余りを受け止める。 */
    private static final long ENDGAME_INVESTMENT_BASE_COST = 45_000_000_000L;
    /*
     * 設備（通常設備・自動営業）に★の解放条件は<b>置かない</b>。
     *
     * 以前はRankごとに必要★を決めていたが、それは「今このRankしか買えない」という
     * 一本道になり、どの系統へ先に投資するかという選択そのものを奪っていた。
     * 代わりに、上のRankほど価格が急に上がること（1段ごとに約5〜7倍。効果の伸びは
     * 1段あたり4〜7割なので、上のRankは1コインあたりの価値が下がる）を歯止めにする。
     * 手が届く範囲では常に「浅く広く買うか、1系統を深く買うか」を選べる。
     *
     * 残っている★条件は設備ではない ― 店構えLv（下の CAFE_LEVELS）、店舗の出店枠
     * （STORE_UNLOCK_STARS）、終盤改装（ENDGAME_INVESTMENT_START_STARS）、
     * アイテムの発見条件。どれも一本道なので、選択の幅を狭めない。
     */
    /** ★の進行に応じて段階的に広がる店舗上限。 */
    private static final int[] STORE_UNLOCK_STARS =
            {4, 22, 57, 101, 144, 187, 230, 270, 310, 345, 385, 425, 458, 483, 502};
    private static final int[] STORE_LIMITS =
            {2, 3, 5, 8, 12, 18, 27, 41, 62, 93, 140, 210, 315, 473, MAX_CAFE_STORES};
    /** ブラウザのタイマー停止を「放置中の売上」として誤加算しないための1回あたり上限。 */
    private static final long MAX_PASSIVE_TICK_MILLIS = 10_000L;

    /**
     * 苦手度の目盛り。1点ぶんを何単位で数えるか。
     *
     * <p>「実行」＝「採点」にしたので、コードを書いている途中の失敗まで全部数える。
     * 失敗1回で1点上がると、試行錯誤しただけで最大まで振り切れてしまう。
     * 内部を4倍の細かさで持ち、<b>失敗1回は1単位（=0.25点）</b>にしてある。
     * 4回失敗して、ようやく従来の1回ぶん。</p>
     */
    private static final int REVIEW_WEIGHT_SCALE = 4;

    /**
     * 苦手度の上限（単位）。表示上は 8点 に相当する。
     *
     * 何度も間違えた1問がそこで止まらず伸び続けると、復習の順番をその問題が独占して
     * 他の問題が出てこなくなる。上限を決めておけば「よく間違える問題ほど出やすい」は
     * 保ったまま、他の問題も混ざる。
     */
    private static final int MAX_REVIEW_WEIGHT = 8 * REVIEW_WEIGHT_SCALE;

    /**
     * 復習の間隔（日）。レベルが上がるほど間を空ける ― 忘却曲線に合わせた確認。
     *
     * <p>初クリアの翌日が最初の期限で、正解するたび次のレベルへ進む。最後まで進むと
     * 4か月ごとの確認になる。期限は「最後に復習した日 + この間隔」で決まるので、
     * ここを変えれば過去の記録にも新しい間隔がそのまま効く（期限日は保存しない）。</p>
     */
    private static final int[] REVIEW_INTERVAL_DAYS = {1, 3, 7, 14, 30, 60, 120};

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

    /** 初回オンボーディングを最後まで完了したか。 */
    private boolean onboardingCompleted;

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
    /**
     * 問題キー -> 苦手度（単位。0〜{@link #MAX_REVIEW_WEIGHT}）。復習の並び順に使う。
     *
     * 実行が通らないと1単位上がり、クリア済みの問題に正解すると1点（=4単位）下がる。
     * ここに載っていないことは「一度も間違えていない」を意味する。
     * 出題そのものを決めるのは苦手度ではなく復習の期限（{@link #reviewSchedule}）で、
     * 苦手度は同じ期限のときにどちらを先に出すかを決めるだけ。
     */
    private final Map<String, Integer> reviewWeight = new LinkedHashMap<>();
    /** ブックマークした問題キー。復習モードで絞り込める。 */
    private final Set<String> bookmarks = new LinkedHashSet<>();
    /**
     * 問題キー -> 復習の予定。忘却曲線でいつ確認するかを決める。
     *
     * 載っていない問題は「初クリアの翌日が期限」として扱う（{@link #reviewSchedule}）。
     */
    private final Map<String, ReviewPlan> reviewPlans = new LinkedHashMap<>();

    /** カフェで現在使える売上。設備を買うと減る。 */
    private long cafeCash;
    /** これまでに提供したコーヒー。減らない成長指標。 */
    private long cafeCups;
    /** これまでに獲得したコイン。支出しても減らず、スペシャルアイテムの発見条件になる。 */
    private long cafeLifetimeCash;
    /** 報酬を受け取った回数。再起動によるラッキー判定の引き直しを防ぐ。 */
    private long cafeRewardSequence;
    /** ラッキーコイン解放抽選を利用者ごとに変える種。進捗リセット時だけ作り直す。 */
    private long cafeLuckyCoinUnlockSeed = ThreadLocalRandom.current().nextLong();
    /** 正解後にラッキーコイン解放抽選を行った回数。再起動による引き直しを防ぐ。 */
    private long cafeLuckyCoinUnlockDrawCount;
    /** 問題クリア報酬を受け取った回数。コンボ報酬の進行を保存する。 */
    private long cafeTaskRewardCount;
    /** 最後に★を獲得してから受け取った自動売上。上限をリロードで引き直さないため保存する。 */
    private long cafePassiveCashSinceTask;
    /**
     * 復習で戻したが、まだ自動売上の枠へ反映していない問題数。
     *
     * 枠の計算には「完成した章の問題数」（教材側しか知らない）が要るので、
     * 復習した時点では数えるだけにして、次に自動売上を集めるときへ持ち越す。
     */
    private int cafeReviewPassiveCredits;
    /** 「今日の1杯目」ボーナスを既に払った日。1日1回に留めるために保存する。 */
    private String cafeDailyFirstRewardDay = "";
    /** 現在営業している店舗数。出店するたび、全店ぶんの注文を同時に受ける。 */
    private int cafeStores = 1;
    /** ★520以降の任意の改装・社会貢献プロジェクトを完了した段階。 */
    private int cafeInvestmentLevel;
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
    /** 復習で連続正解した、重複しない問題。失敗すると空に戻る。 */
    private final Set<String> cafeMasteryTaskRun = new LinkedHashSet<>();
    /** 復習で再正解した問題。章をもう一度仕上げたかの判定と、ブランド成長に使う。 */
    private final Set<String> cafeMasteryTasks = new LinkedHashSet<>();
    /** 復習問題を最後に正解した日。日が変わったら当日分を空にする。 */
    private String cafeMasteryDay = "";
    /** {@link #cafeMasteryDay} に復習で正解した、重複しない問題。 */
    private final Set<String> cafeMasteryDayTasks = new LinkedHashSet<>();
    /** 初回答・復習を問わず、現在連続正解中の重複しない確認クイズ。 */
    private final Set<String> cafeQuizMasteryRun = new LinkedHashSet<>();
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

    /** 売上倍率を増やさず、称号と終盤のコイン使途を増やす任意投資。 */
    public record CafeInvestment(
            int level,
            String name,
            String emoji,
            String description,
            int requiredStars,
            long cost) {
    }

    public record InvestmentPurchaseResult(
            boolean purchased,
            String error,
            CafeInvestment investment) {
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
            List<CafeItemEffect> effects) {

        boolean byAchievement() {
            return !unlockAchievement.isEmpty();
        }

        /** その効果の値。このアイテムが持たない効果なら0。 */
        int effectValue(String type) {
            for (CafeItemEffect effect : effects) {
                if (effect.type().equals(type)) {
                    return effect.value();
                }
            }
            return 0;
        }

        boolean hasEffect(String type) {
            return effectValue(type) != 0;
        }
    }

    /**
     * アイテム1つが持つ効果。
     *
     * <p>1つのアイテムが複数の効果を持てる。アイテムは1種類ずつしか持てないので
     * （{@code cafeItems} が集合で、購入時に重複を弾く）、近い効果を1枚に束ねるほど
     * 1枚あたりの特別感が上がる。効果の合計は束ねる前と変えない。</p>
     */
    public record CafeItemEffect(String type, int value) {
    }

    private static CafeItemEffect fx(String type, int value) {
        return new CafeItemEffect(type, value);
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

    /**
     * カフェ計算に必要な、教材側で算出した学習進捗。
     *
     * 章にどの問題が属するかは教材側しか知らないので、章単位の集計はここで受け取る。
     */
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
                    "毎日通いたくなる空間 · 連続1日ごと 今日の1杯目 +3%", 500, 1, "streak", 3),

            new CafeUpgrade("signboard", "手書きの看板", "🪧",
                    "店を見つけてもらいやすくする · 注文売上 +6%", 7_000, 2, "sales", 6),
            new CafeUpgrade("hand_grinder", "手挽きミル", "🫘",
                    "抽出を並行できる · 毎注文 +2杯", 14_000, 2, "cups", 2),
            new CafeUpgrade("dripper", "ドリップスタンド", "🫗",
                    "繁忙時の抽出を安定させる · 章ボーナス +20%", 4_900, 2, "chapter", 20),
            new CafeUpgrade("cookie_plate", "試食クッキープレート", "🍪",
                    "正解を祝うひと口サービス · 正解チップ +50%", 2_100, 2, "tips", 50),
            new CafeUpgrade("window_seat", "窓際の指定席", "🪟",
                    "毎日の常連席をつくる · 連続1日ごと 今日の1杯目 +6%", 3_500, 2, "streak", 6),

            new CafeUpgrade("grinder", "セラミックグラインダー", "⚙️",
                    "豆の品質で客単価アップ · 注文売上 +13%", 50_000, 3, "sales", 13),
            new CafeUpgrade("brew_station", "第2抽出ステーション", "🫖",
                    "二つの注文を同時に作る · 毎注文 +4杯", 100_000, 3, "cups", 4),
            new CafeUpgrade("showcase", "焼き菓子ケース", "🧁",
                    "章末のまとめ買いを増やす · 章ボーナス +30%", 35_000, 3, "chapter", 30),
            new CafeUpgrade("latte_art", "ラテアート練習台", "🎨",
                    "正解祝いの一杯を特別に · 正解チップ +80%", 15_000, 3, "tips", 80),
            new CafeUpgrade("study_table", "学習者の大テーブル", "📚",
                    "学び続ける常連が集まる · 連続1日ごと 今日の1杯目 +9%", 25_000, 3, "streak", 9),

            new CafeUpgrade("espresso", "エスプレッソマシン", "☕",
                    "高単価メニューを提供 · 注文売上 +23%", 300_000, 4, "sales", 23),
            new CafeUpgrade("seats", "くつろぎテーブル", "🪑",
                    "同時に迎えられる客を増やす · 毎注文 +8杯", 600_000, 4, "cups", 8),
            new CafeUpgrade("weekend_event", "週末コーヒーイベント", "🎪",
                    "章末にお客さんを集める · 章ボーナス +45%", 210_000, 4, "chapter", 45),
            new CafeUpgrade("dessert_pairing", "デザートペアリング", "🍰",
                    "知識と味の組み合わせを祝う · 正解チップ +110%", 90_000, 4, "tips", 110),
            new CafeUpgrade("loyalty_board", "常連ネームボード", "🏷️",
                    "連続来店を店内で称える · 連続1日ごと 今日の1杯目 +12%", 150_000, 4, "streak", 12),

            new CafeUpgrade("roaster", "小型ロースター", "🔥",
                    "自家焙煎でブランド化 · 注文売上 +38%", 1_500_000, 5, "sales", 38),
            new CafeUpgrade("kitchen", "増設キッチン", "🍳",
                    "大量の注文へ対応 · 毎注文 +16杯", 3_000_000, 5, "cups", 16),
            new CafeUpgrade("terrace", "テラス貸切プラン", "⛱️",
                    "章末に団体客を呼ぶ · 章ボーナス +60%", 1_050_000, 5, "chapter", 60),
            new CafeUpgrade("tasting_flight", "飲み比べフライト", "🥃",
                    "正解後の体験価値を上げる · 正解チップ +145%", 450_000, 5, "tips", 145),
            new CafeUpgrade("daily_roast_log", "本日の焙煎ログ", "📋",
                    "学習と焙煎を毎日記録 · 連続1日ごと 今日の1杯目 +15%", 750_000, 5, "streak", 15),

            new CafeUpgrade("pos", "POSレジ", "🖥️",
                    "販売データで価格を最適化 · 注文売上 +58%", 8_000_000, 6, "sales", 58),
            new CafeUpgrade("mobile", "モバイルオーダー端末", "📱",
                    "店外からの注文も受ける · 毎注文 +32杯", 16_000_000, 6, "cups", 32),
            new CafeUpgrade("subscription", "豆の定期便", "📦",
                    "章末に定期購入が入る · 章ボーナス +80%", 5_600_000, 6, "chapter", 80),
            new CafeUpgrade("barista_school", "バリスタ講座", "🎓",
                    "正解の価値を伝える接客 · 正解チップ +180%", 2_400_000, 6, "tips", 180),
            new CafeUpgrade("commuter_pass", "常連パスポート", "🪪",
                    "日々の来店を習慣化 · 連続1日ごと 今日の1杯目 +18%", 4_000_000, 6, "streak", 18),

            new CafeUpgrade("second_store", "フランチャイズ本部", "🏢",
                    "全店の販売戦略を統一する · 注文売上 +88%", 40_000_000, 7, "sales", 88),
            new CafeUpgrade("delivery", "デリバリー車両", "🛵",
                    "広い地域の注文へ対応 · 毎注文 +64杯", 80_000_000, 7, "cups", 64),
            new CafeUpgrade("factory", "焙煎ファクトリー", "🏭",
                    "章末に全店へ豆を出荷 · 章ボーナス +105%", 28_000_000, 7, "chapter", 105),
            new CafeUpgrade("vip_counter", "VIPカウンター", "💎",
                    "クイズ好きの特別席 · 正解チップ +220%", 12_000_000, 7, "tips", 220),
            new CafeUpgrade("daily_newsletter", "毎朝のニュースレター", "📰",
                    "常連へ学びの話題を届ける · 連続1日ごと 今日の1杯目 +21%", 20_000_000, 7, "streak", 21),

            new CafeUpgrade("flagship_store", "フラッグシップ店", "🏛️",
                    "街の名所になり客単価上昇 · 注文売上 +128%", 260_000_000L, 8, "sales", 128),
            new CafeUpgrade("robot_barista", "ロボットバリスタ", "🤖",
                    "大量注文を正確に抽出 · 毎注文 +128杯", 520_000_000L, 8, "cups", 128),
            new CafeUpgrade("catering", "法人ケータリング", "🚚",
                    "章末に大型注文を獲得 · 章ボーナス +135%", 182_000_000L, 8, "chapter", 135),
            new CafeUpgrade("concierge", "コーヒーコンシェルジュ", "🤵",
                    "知識に合わせて一杯を提案 · 正解チップ +260%", 78_000_000L, 8, "tips", 260),
            new CafeUpgrade("habit_app", "学習習慣アプリ", "📲",
                    "毎日の来店を楽しく通知 · 連続1日ごと 今日の1杯目 +24%", 130_000_000L, 8, "streak", 24),

            new CafeUpgrade("airport_store", "空港ラウンジ店", "✈️",
                    "世界の旅行客へ販売 · 注文売上 +183%", 2_000_000_000L, 9, "sales", 183),
            new CafeUpgrade("smart_kitchen", "スマートキッチン", "🦾",
                    "全工程を自動連携 · 毎注文 +228杯", 4_000_000_000L, 9, "cups", 228),
            new CafeUpgrade("coffee_festival", "都市コーヒーフェス", "🎆",
                    "章末に街じゅうを集客 · 章ボーナス +170%", 1_400_000_000L, 9, "chapter", 170),
            new CafeUpgrade("members_lounge", "会員制ラウンジ", "🛋️",
                    "正解を語り合う上質な席 · 正解チップ +300%", 600_000_000L, 9, "tips", 300),
            new CafeUpgrade("mentor_club", "朝活メンタークラブ", "🌅",
                    "仲間と毎日学び続ける · 連続1日ごと 今日の1杯目 +27%", 1_000_000_000L, 9, "streak", 27),

            new CafeUpgrade("global_brand", "グローバルブランド", "🌍",
                    "世界共通の一杯へ · 注文売上 +258%", 15_000_000_000L, 10, "sales", 258),
            new CafeUpgrade("coffee_lab", "全自動コーヒーラボ", "🧪",
                    "研究設備で超大量抽出 · 毎注文 +388杯", 30_000_000_000L, 10, "cups", 388),
            new CafeUpgrade("world_expo", "ワールドコーヒーEXPO", "🎡",
                    "章末に世界規模の注文 · 章ボーナス +210%", 10_500_000_000L, 10, "chapter", 210),
            new CafeUpgrade("founders_club", "創業者クラブ", "👑",
                    "最高の学びへ最大級の祝福 · 正解チップ +350%", 4_500_000_000L, 10, "tips", 350),
            new CafeUpgrade("learning_retreat", "学習リトリート", "🏝️",
                    "学びを生活の一部にする · 連続1日ごと 今日の1杯目 +30%", 7_500_000_000L, 10, "streak", 30),

            new CafeUpgrade("quantum_campaign", "量子級ブランドキャンペーン", "🪐",
                    "開発者コミュニティ全体へ届ける · 注文売上 +360%", 110_000_000_000L, 11, "sales", 360),
            new CafeUpgrade("orbital_roastery", "軌道ロースタリー", "🛰️",
                    "軌道上の巨大設備で抽出 · 毎注文 +640杯", 220_000_000_000L, 11, "cups", 640),
            new CafeUpgrade("developer_summit", "世界開発者サミット", "🧑‍🚀",
                    "章末に世界の学習者が集う · 章ボーナス +260%", 77_000_000_000L, 11, "chapter", 260),
            new CafeUpgrade("knowledge_vault", "知識の宝物庫", "🏆",
                    "正解の知識を価値ある体験へ · 正解チップ +400%", 33_000_000_000L, 11, "tips", 400),
            new CafeUpgrade("learning_guild", "世界学習ギルド", "🤝",
                    "仲間と学ぶ文化を世界へ · 連続1日ごと 今日の1杯目 +33%", 55_000_000_000L, 11, "streak", 33),

            new CafeUpgrade("java_legacy", "Javaレガシー殿堂", "🏛️",
                    "積み重ねた学びを永続するブランドへ · 注文売上 +500%", 820_000_000_000L, 12, "sales", 500),
            new CafeUpgrade("planetary_brew", "惑星間ブリューシステム", "🚀",
                    "惑星規模の注文を同時抽出 · 毎注文 +1024杯", 1_640_000_000_000L, 12, "cups", 1_024),
            new CafeUpgrade("mastery_congress", "マスタリー世界会議", "🎓",
                    "全章の学びを祝う最大イベント · 章ボーナス +320%", 574_000_000_000L, 12, "chapter", 320),
            new CafeUpgrade("hall_of_fame_counter", "殿堂カウンター", "🥇",
                    "最高難度の正解を盛大に祝う · 正解チップ +450%", 246_000_000_000L, 12, "tips", 450),
            new CafeUpgrade("lifelong_academy", "生涯学習アカデミー", "♾️",
                    "学び続ける文化を完成させる · 連続1日ごと 今日の1杯目 +36%", 410_000_000_000L, 12, "streak", 36));

    /**
     * スペシャルアイテム12種。1種類ずつしか持てず、<b>1枚が持つ効果は1つだけ</b>。
     *
     * <p>効果を1枚へ束ねると、そのカードが何のカードなのか言えなくなる
     * （「一発で解いた」と「5回以上粘った」のように条件が正反対のものまで同居した）。
     * 12枠に収まるところまで効果を削り、残したものは全て単独カードにしてある。</p>
     *
     * <p>削った効果の値は、同じ軸に残したカードへ寄せている ―
     * 当日ボーナスはコンボスタンプ帳（5問ごと6倍→7倍）へ、
     * ブランド+0.25は生涯学習トロフィー（+10%→+13%）へ、
     * 復習ぶんの重み付け3つは復習ノート（倍率を4倍）へ。</p>
     *
     * <p>解放は2通り。{@code unlockAchievement} が空なら★数と累計コイン（学習の節目）、
     * 値があれば {@link #ACHIEVEMENT_NOTES} の達成条件で解放する。
     * 最後の2つ（復習ノート・生涯学習トロフィー）だけは条件が重い。</p>
     */
    private static final List<CafeItem> CAFE_ITEMS = List.of(
            new CafeItem("lucky_coin", "ラッキーコイン", "🪙",
                    "問題・章・クイズ報酬で5%の確率で大当たり（獲得コイン+100%）",
                    77_777L, 0, 0L, "lucky_coin_draw",
                    List.of(fx("lucky_double", 2), fx("lucky_chance", 5))),
            new CafeItem("golden_bean", "コンボスタンプ帳", "🗒️",
                    "問題を5問クリアするたび、その問題の獲得コインが必ず7倍",
                    80_000L, 0, 0L, "same_day_15",
                    List.of(fx("task_combo", 7))),
            new CafeItem("first_try_tamper", "一発仕上げのタンパー", "🥄",
                    "ヒントなし・1回の提出でクリアした問題の獲得コインが+20%",
                    15_000L, 0, 0L, "chapter_no_hint",
                    List.of(fx("first_try_percent", 20))),
            new CafeItem("persistence_dripper", "粘りのドリッパー", "💧",
                    "5回以上提出してクリアした問題の獲得コインが2倍",
                    12_000L, 0, 0L, "persistent_clear",
                    List.of(fx("retry_double", 2))),
            new CafeItem("attendance_calendar", "皆勤の日めくり", "📅",
                    "「今日の1杯目」に数える連続日数の上限が7日から10日に広がる",
                    120_000L, 0, 0L, "streak_7",
                    List.of(fx("streak_cap", 10))),
            new CafeItem("food_truck", "移動販売トラック", "🚚",
                    "注文の集客が2店舗ぶん増える（店舗が少ないうちほど効く）",
                    900_000L, 0, 0L, "store_5",
                    List.of(fx("store_bonus", 2))),
            new CafeItem("quiz_crown", "ひらめきメガホン", "📣",
                    "確認クイズの初回正解チップが必ず20倍",
                    250_000L, 0, 0L, "quiz_streak_20",
                    List.of(fx("quiz_multiplier", 20))),
            new CafeItem("fortune_cat", "祝福のホールケーキ", "🎂",
                    "章を初めて制覇したときの獲得コインが必ず4.5倍",
                    2_000_000L, 0, 0L, "chapter_one_day",
                    List.of(fx("chapter_percent", 450))),
            new CafeItem("fever_bell", "フランチャイズ地図", "🗺️",
                    "新店舗の出店費用がいつでも25%OFF",
                    20_000_000L, 170, 50_000_000L, "",
                    List.of(fx("expansion_discount", 25))),
            new CafeItem("java_relic", "マイスター工具箱", "🧰",
                    "すべての設備アップグレード費用がいつでも20%OFF",
                    200_000_000L, 240, 500_000_000L, "",
                    List.of(fx("equipment_discount", 20))),

            // ここから2つは取得条件が重い。全問の3分の1以上の復習、または25問連続の無傷クリア。
            new CafeItem("quiz_festival_pass", "復習ノート", "📖",
                    "復習で育つブランド倍率の成長が4倍になる",
                    110_000_000_000L, 0, 0L, "review_200",
                    List.of(fx("review_brand_multiplier", 4))),
            new CafeItem("lifelong_trophy", "生涯学習トロフィー", "🏆",
                    "問題・章・クイズで得るすべての学習報酬が13%増加",
                    210_000_000_000L, 0, 0L, "flawless_25",
                    List.of(fx("mastery_bonus", 13))));

    /**
     * 達成型アイテムの解放条件。画面のカードにそのまま出す。
     *
     * 下2つ（{@code review_200} / {@code flawless_25}）が重い条件。全574問の3分の1以上を
     * 復習するか、25問を無傷で連続クリアしないと届かない。
     */
    private static final Map<String, String> ACHIEVEMENT_NOTES = Map.ofEntries(
            Map.entry("lucky_coin_draw", "問題または復習問題へ正解するたび、1%の確率で解放"),
            Map.entry("same_day_15", "同じ日に異なる15問を初クリアまたは復習で正解"),
            Map.entry("streak_7", "7日連続で学習"),
            Map.entry("quiz_streak_20", "確認クイズの異なる20問に連続正解（初回答・復習どちらでも可）"),
            Map.entry("chapter_no_hint", "1つの章をヒントなしで初制覇、または復習で全問に再正解"),
            Map.entry("chapter_one_day", "1つの章を同じ日に初制覇、または同じ日に全問復習"),
            Map.entry("store_5", "店舗を5店まで広げる"),
            Map.entry("persistent_clear", "クリア済みの1問へ累計10回以上提出"),
            Map.entry("review_200", "復習で異なる200問に正解する"),
            Map.entry("flawless_25",
                    "ヒントなし・1回の提出で25問連続クリア、または復習で異なる25問に連続正解"));

    /**
     * アプリ画面を表示している間だけ動く自動営業設備。
     *
     * 率は「次の問題を初クリアしたときの売上」に対する1分あたりの割合。
     * 最上位でも5%/分、かつ次の★まで5問分が上限。
     * 上限までは最短で100分かかり、オフライン中は稼働しない。
     */
    private static final List<CafeAutomation> CAFE_AUTOMATION = List.of(
            new CafeAutomation("warming_pot", "保温ポット", "🫖",
                    "作り置きを少しずつ販売 · 学習1回分の0.5%/分", 2_500L, 1, 50),
            new CafeAutomation("self_service", "セルフサービス台", "🥤",
                    "会計を待たずに販売 · 学習1回分の0.9%/分", 20_000L, 2, 90),
            new CafeAutomation("order_kiosk", "注文キオスク", "🖥️",
                    "注文と決済を自動化 · 学習1回分の1.3%/分", 150_000L, 3, 130),
            new CafeAutomation("auto_brew_line", "自動抽出ライン", "⚙️",
                    "抽出工程を自動連携 · 学習1回分の1.7%/分", 1_000_000L, 4, 170),
            new CafeAutomation("unmanned_cafe", "自動会計システム", "💳",
                    "提供後の会計まで自動化 · 学習1回分の2.1%/分", 6_000_000L, 5, 210),
            new CafeAutomation("serving_robot", "配膳ロボット", "🤖",
                    "客席への提供も自動化 · 学習1回分の2.5%/分", 30_000_000L, 6, 250),
            new CafeAutomation("demand_ai", "AI需要予測", "📈",
                    "来店予測で作り置きを最適化 · 学習1回分の3%/分", 150_000_000L, 7, 300),
            new CafeAutomation("smart_store_control", "スマート店舗管制", "🛰️",
                    "全設備を一括制御 · 学習1回分の3.5%/分", 750_000_000L, 8, 350),
            new CafeAutomation("round_clock_cafe", "24時間無人店舗", "🌙",
                    "表示中の店舗運営を完全無人化 · 学習1回分の4%/分", 4_000_000_000L, 9, 400),
            new CafeAutomation("autonomous_cafe", "完全自律カフェ", "🦾",
                    "注文から提供まで自律運転 · 学習1回分の4.4%/分", 20_000_000_000L, 10, 440),
            new CafeAutomation("learning_grid", "学習グリッド管制", "🌐",
                    "世界の店舗を共同制御 · 学習1回分の4.7%/分", 100_000_000_000L, 11, 470),
            new CafeAutomation("mastery_ai", "マスタリー運営AI", "🧠",
                    "全店舗の注文を最適化 · 学習1回分の5%/分", 400_000_000_000L, 12, 500));

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    /**
     * 1問の復習予定。
     *
     * @param level    どの間隔まで進んだか（{@link #REVIEW_INTERVAL_DAYS} の添字）
     * @param lastAt   最後に復習した日。ここに間隔を足したものが次の期限
     * @param lastFailAt 最後に失敗した日。同じ日に失敗してから通した正解は「危なかった」
     *                   とみなしてレベルを1つ戻すために持つ
     */
    public record ReviewPlan(int level, String lastAt, String lastFailAt) {
    }

    /** 復習の期限。画面はこれを見て「期限切れ」「あと○日」を出す。 */
    public record ReviewDue(int level, String dueDate, int daysUntilDue) {

        public boolean overdue() {
            return daysUntilDue <= 0;
        }
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

    /** その問題の苦手度。一度も間違えていなければ 0。 */
    public synchronized int reviewWeight(String taskKey) {
        return reviewWeight.getOrDefault(taskKey, 0);
    }

    public synchronized boolean isBookmarked(String taskKey) {
        return bookmarks.contains(taskKey);
    }

    /**
     * 初回オンボーディングを表示すべきか。
     *
     * <p>フラグ導入前から使っている人には完了フラグが無い。その場合も、下書き・提出・
     * クリアなどの学習進捗が1つでもあれば初回利用者ではないと判断し、通常画面を出す。</p>
     */
    public synchronized boolean isOnboardingRequired() {
        return !onboardingCompleted && !hasLearningProgress();
    }

    public synchronized boolean isOnboardingCompleted() {
        return onboardingCompleted;
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
        m.put("onboardingRequired", isOnboardingRequired());
        m.put("onboardingCompleted", onboardingCompleted);
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
        long brandMultiplierBasisPoints = cafeBrandMultiplierBasisPoints(learning);
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
        long nextOrderCash = cafeCashForCups(orderCups, learning);
        cafe.put("nextOrderCash", nextOrderCash);
        cafe.put("passiveCashPerMinute", cafePassiveCashPerMinute(learning));
        long passiveCap = cafePassiveCashCap(learning);
        cafe.put("passiveCashCap", passiveCap);
        // 復習で戻した枠は次のtickで反映されるが、表示だけは先に差し引いて見せる
        long passiveSpent = Math.max(0L, cafePassiveCashSinceTask
                - saturatedMultiply(nextOrderCash, cafeReviewPassiveCredits));
        cafe.put("passiveCashRemaining", Math.max(0L, passiveCap - passiveSpent));
        CafeAutomation activeAutomation = currentCafeAutomation();
        cafe.put("passiveRateBasisPoints", activeAutomation == null
                ? 0 : activeAutomation.rateBasisPointsPerMinute());
        // 全報酬へ掛かるのは販売戦略だけ。常連サービスは dailyFirstBonusPercent が持つ
        cafe.put("bonusPercent", cafeSalesBonusPercent());
        cafe.put("salesBonusPercent", cafeSalesBonusPercent());
        cafe.put("dailyFirstBonusPercent", cafeDailyFirstBonusPercent());
        cafe.put("dailyFirstBonusReady",
                !LocalDate.now().toString().equals(cafeDailyFirstRewardDay));
        cafe.put("streakDays", effectiveStreakDays());
        cafe.put("extraCups", cafeExtraCups());
        cafe.put("chapterBonusPercent", cafeChapterBonusPercent());
        cafe.put("quizTipPercent", cafeQuizTipPercent());
        cafe.put("clearedChapters", learning.clearedChapters());
        cafe.put("masteredChapterTasks", learning.masteredChapterTasks());
        cafe.put("brandMultiplierBasisPoints", brandMultiplierBasisPoints);
        cafe.put("reviewBrandBasisPoints", cafeReviewBrandBasisPoints());
        cafe.put("reviewedTasks", cafeMasteryTasks.size());
        cafe.put("reviewedTaskPercent", reviewedTaskPercent());
        cafe.put("equipmentDiscountPercent", equipmentDiscountPercent());
        cafe.put("storeCount", cafeStores);
        cafe.put("maxStores", MAX_CAFE_STORES);
        cafe.put("storeLimit", Math.max(cafeStores, progressStoreLimit));
        cafe.put("nextStoreUnlockStars", canExpandNetwork || maximumNetwork
                ? null : nextCafeStoreUnlockStars());
        int nextStoreGain = canExpandNetwork ? nextCafeStoreGain(progressStoreLimit) : 0;
        cafe.put("nextStoreGain", nextStoreGain);
        cafe.put("nextStoreCount", canExpandNetwork ? cafeStores + nextStoreGain : null);
        cafe.put("expansionCost", canExpandNetwork ? nextCafeExpansionCost() : null);
        cafe.put("investmentLevel", cafeInvestmentLevel);
        cafe.put("investmentAvailableLevel", currentCafeInvestmentAvailableLevel());
        CafeInvestment nextInvestment = nextCafeInvestment();
        cafe.put("endgameInvestment", cafeInvestmentVisible() && nextInvestment != null
                ? cafeInvestmentToClientJson(nextInvestment)
                : null);
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
            List<Object> effects = new ArrayList<>();
            for (CafeItemEffect effect : item.effects()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", effect.type());
                e.put("value", effect.value());
                effects.add(e);
            }
            value.put("effects", effects);
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
     * 初クリアした注文の報酬。客単価は売上へ掛ける。
     *
     * {@code taskKey} は、ヒントを使ったか・何回で通ったかを見るアイテム
     * （一発仕上げのタンパー、粘りのドリッパー）のために受け取る。
     */
    public synchronized CafeAward rewardTask(CafeLearningProgress learning, String taskKey) {
        cafePassiveCashSinceTask = 0;
        // 枠が満タンに戻るので、復習で戻しておいたぶんは使わずに捨てる
        cafeReviewPassiveCredits = 0;
        resetCafePassiveClock();
        // テストケース数ではなく店舗の集客力で販売数を増やす。
        // 店舗ごとに同じ注文が入り、章クリアで育つブランド倍率を最後に掛ける。
        long cups = cupsPerNetworkOrderWithUpgrades();
        long cash = cafeCashForCups(cups, learning);
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
        long cash = cafeCashForCups(cups, learning);
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
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
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
            String sessionId, CafeLearningProgress learning) {
        cafePassiveSessionId = sessionId;
        cafePassiveLastTickNanos = System.nanoTime();
        cafePassiveRemainder = 0;
        return new PassiveSalesResult(0, cafePassiveCashPerMinute(learning), true);
    }

    /** 表示中のアプリ画面からの定期連絡ぶんだけ、自動売上を加算する。 */
    public synchronized PassiveSalesResult collectCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        if (sessionId == null || !sessionId.equals(cafePassiveSessionId)) {
            return new PassiveSalesResult(0, cafePassiveCashPerMinute(learning), false);
        }
        long now = System.nanoTime();
        long elapsedMillis = Math.max(0L, (now - cafePassiveLastTickNanos) / 1_000_000L);
        elapsedMillis = Math.min(elapsedMillis, MAX_PASSIVE_TICK_MILLIS);
        cafePassiveLastTickNanos = now;

        long ratePerMinute = cafePassiveCashPerMinute(learning);
        // 復習で戻した枠は、1問ぶんの売上額が分かるここで初めて使う
        consumeReviewPassiveCredits(learning);
        long remaining = Math.max(0L, cafePassiveCashCap(learning) - cafePassiveCashSinceTask);
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
            String sessionId, CafeLearningProgress learning) {
        PassiveSalesResult result = collectCafePassiveSales(sessionId, learning);
        if (sessionId != null && sessionId.equals(cafePassiveSessionId)) {
            cafePassiveSessionId = null;
            cafePassiveLastTickNanos = 0;
            cafePassiveRemainder = 0;
        }
        return new PassiveSalesResult(result.cash(), result.cashPerMinute(), false);
    }

    /**
     * 復習で戻した枠を、実際に「受け取った自動売上」から引く。
     *
     * <p>復習した時点では1問ぶんの売上額（完成した章の問題数が要る）が分からないので、
     * 問題数だけ数えておいて、自動売上を集めるここで換算する。0を下回らせないので、
     * 何問復習しても枠が既定の5問分より広くなることはない。</p>
     */
    private void consumeReviewPassiveCredits(CafeLearningProgress learning) {
        if (cafeReviewPassiveCredits <= 0) {
            return;
        }
        long oneTask = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        long refund = saturatedMultiply(oneTask, cafeReviewPassiveCredits);
        cafeReviewPassiveCredits = 0;
        cafePassiveCashSinceTask = Math.max(0L, cafePassiveCashSinceTask - refund);
        saveEventually();
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

    /**
     * 終盤の任意プロジェクトを1段階完了する。
     *
     * <p>報酬倍率は増やさない。学習コンテンツが増えたときに、
     * 20問ごとに新しい使い道を自動で用意するための長期的なコイン消費先。</p>
     */
    public synchronized InvestmentPurchaseResult purchaseCafeInvestment() {
        CafeInvestment investment = nextCafeInvestment();
        if (investment == null) {
            return new InvestmentPurchaseResult(false, "改装プロジェクトは上限です", null);
        }
        if (cleared.size() < investment.requiredStars()) {
            return new InvestmentPurchaseResult(false,
                    "次の改装プロジェクトには★" + investment.requiredStars() + "が必要です",
                    investment);
        }
        if (cafeCash < investment.cost()) {
            return new InvestmentPurchaseResult(false,
                    "改装プロジェクトに必要なコインが足りません", investment);
        }
        cafeCash -= investment.cost();
        cafeInvestmentLevel = investment.level();
        resetCafePassiveClock();
        saveSoon();
        return new InvestmentPurchaseResult(true, null, investment);
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

        CafeItem luckyCoin = ownedItemWithEffect("lucky_double");
        if (luckyCoin != null
                && isLuckyHit(cafeRewardSequence, luckyCoin.effectValue("lucky_chance"))) {
            int times = luckyCoin.effectValue("lucky_double");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(luckyCoin.emoji() + " " + luckyCoin.name()
                    + "大当たり！ 獲得コイン+" + ((times - 1) * 100) + "%");
        }

        CafeItem comboBook = ownedItemWithEffect("task_combo");
        if (trigger.equals("task") && comboBook != null
                && cafeTaskRewardCount % TASK_COMBO_INTERVAL == 0) {
            int times = comboBook.effectValue("task_combo");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(comboBook.emoji() + " " + comboBook.name()
                    + "完成！ " + TASK_COMBO_INTERVAL + "問目ボーナス×" + times);
        }

        // 常連サービス系統は「その日の最初の1問」にだけ乗る。全報酬へ足すと
        // 販売戦略系統と同じ変数になり、同じ買い物が2系統に分かれてしまう。
        // ここで初めて払う日を記録するので、同じ日に何問解いても1回で終わる。
        int dailyFirstPercent = cafeDailyFirstBonusPercent();
        if (trigger.equals("task") && dailyFirstPercent > 0) {
            String today = LocalDate.now().toString();
            if (!today.equals(cafeDailyFirstRewardDay)) {
                cafeDailyFirstRewardDay = today;
                rewardedCash = applyPercent(rewardedCash, 100L + dailyFirstPercent);
                itemEvents.add("☀️ 今日の1杯目 +" + dailyFirstPercent + "%（連続"
                        + effectiveStreakDays() + "日）");
            }
        }

        Cleared clearedTask = taskKey == null ? null : cleared.get(taskKey);

        CafeItem tamper = ownedItemWithEffect("first_try_percent");
        if (trigger.equals("task") && tamper != null && clearedTask != null
                && clearedTask.hintsUsed() == 0 && clearedTask.attempts() <= 1) {
            int percent = tamper.effectValue("first_try_percent");
            rewardedCash = applyPercent(rewardedCash, 100L + percent);
            itemEvents.add(tamper.emoji() + " " + tamper.name() + "で一発クリア+" + percent + "%");
        }

        CafeItem dripper = ownedItemWithEffect("retry_double");
        if (trigger.equals("task") && dripper != null && clearedTask != null
                && clearedTask.attempts() >= RETRY_BONUS_ATTEMPTS) {
            int times = dripper.effectValue("retry_double");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(dripper.emoji() + " " + dripper.name() + "で粘りボーナス×" + times);
        }

        CafeItem quizMegaphone = ownedItemWithEffect("quiz_multiplier");
        if (trigger.equals("quiz") && quizMegaphone != null) {
            int times = quizMegaphone.effectValue("quiz_multiplier");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(quizMegaphone.emoji() + " " + quizMegaphone.name()
                    + "で正解チップ×" + times);
        }

        CafeItem chapterCake = ownedItemWithEffect("chapter_percent");
        if (trigger.equals("chapter") && chapterCake != null) {
            int percent = chapterCake.effectValue("chapter_percent");
            rewardedCash = applyPercent(rewardedCash, percent);
            itemEvents.add(chapterCake.emoji() + " " + chapterCake.name()
                    + "で章制覇ボーナス×" + (percent / 100.0));
        }

        CafeItem lifelongTrophy = ownedItemWithEffect("mastery_bonus");
        if (lifelongTrophy != null) {
            int percent = lifelongTrophy.effectValue("mastery_bonus");
            rewardedCash = applyPercent(rewardedCash, 100L + percent);
            itemEvents.add(lifelongTrophy.emoji() + " " + lifelongTrophy.name()
                    + "で学習報酬+" + percent + "%");
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
     * 全問を終えてからカフェを開いた人にも、節目型アイテムの未所持分を贈る。
     *
     * <p>問題・章の売上は初回だけなので、設備を買わずに完走すると、その後は生涯売上を
     * 大きく増やせず後半アイテムが事実上取得不能になる。完走時だけ、現在の★条件を
     * 満たす達成型以外のアイテムを記念品として所持済みにする。通常どおり育てた人は
     * すでに所持しているため変化せず、何度呼んでも重複しない。</p>
     *
     * @return 今回贈ったアイテム数。追加がなければ0
     */
    public synchronized int ensureCafeCompletionCatchUp(
            int currentCurriculumClearedTasks, int totalTaskCount) {
        if (totalTaskCount <= 0 || currentCurriculumClearedTasks < totalTaskCount) {
            return 0;
        }
        int added = 0;
        for (CafeItem item : CAFE_ITEMS) {
            if (!item.byAchievement()
                    && currentCurriculumClearedTasks >= item.unlockStars()
                    && cafeItems.add(item.id())) {
                added++;
            }
        }
        if (added > 0) {
            saveSoon();
        }
        return added;
    }

    /**
     * 提出結果を、苦手度と「取り逃したアイテムの復習チャレンジ」へ記録する。
     *
     * 苦手度は、間違えたら上げてクリア済みの問題に正解したら下げる。復習モードは
     * この値を出題頻度に使う（{@link #reviewWeight(String)}）。初回の学習中に間違えた分も
     * 数えるので、「一度でも間違えた問題」はクリア後の復習で自然と出やすくなる。
     *
     * 復習チャレンジの方は、同じ問題の連打では数を増やさず、失敗した提出は
     * 10問連続の記録だけを切る。★と通常報酬は呼び出し側の初回判定で引き続き付与しない。
     *
     * カフェへ渡すのは「倍率」と「自動売上の枠」だけで、コインは1枚も払わない。
     * クリア済みの問題は何度でも解き直せるので、支払うと無限に稼げてしまう。
     * どちらも1問につき1回しか数えないため、上限は問題数で構造的に決まる。
     *
     * <p>この関数は {@code ApiHandler.doSubmit} で {@code markCleared} より<b>前</b>に
     * 呼ばれる。初クリアの時点ではまだ {@code cleared} に入っていないので、
     * 下の早期returnで抜ける ― つまり初クリアが復習ぶんの報酬を二重取りしない。
     * 順番を入れ替えるとこの前提が崩れる。</p>
     */
    public synchronized void recordMasterySubmission(String taskKey, boolean passed) {
        // 下げるのはクリア済みの問題に正解したときだけ。まだ通っていない問題で
        // 1ケースだけ通った提出などを「復習で正解」と数えないため。
        // 失敗は1単位（=0.25点）だけ上げる。書いている途中の失敗も全部ここを通るので、
        // 1回で1点上げると試行錯誤しただけで振り切れてしまう。
        // 正解したときは1点（=4単位）まとめて下げる。
        boolean changed = passed
                ? cleared.containsKey(taskKey) && addReviewWeight(taskKey, -REVIEW_WEIGHT_SCALE)
                : addReviewWeight(taskKey, 1);
        boolean scheduled = updateReviewPlan(taskKey, passed);
        changed |= scheduled;
        if (passed) {
            // 初クリア前にも呼ばれる共通経路なので、通常問題と復習問題を同じ1回として抽選できる。
            changed |= drawLuckyCoinUnlock();
        }

        if (!passed || !cleared.containsKey(taskKey)) {
            // 復習の合間に未クリア問題で失敗した場合も「連続正解」ではなくなる。
            if (!passed && !cafeMasteryTaskRun.isEmpty()) {
                cafeMasteryTaskRun.clear();
                changed = true;
            }
            if (changed) {
                saveSoon();
            }
            return;
        }

        String today = LocalDate.now().toString();
        if (!today.equals(cafeMasteryDay)) {
            cafeMasteryDay = today;
            cafeMasteryDayTasks.clear();
        }
        cafeMasteryTaskRun.add(taskKey);
        // ブランド倍率が数えるのはこの集合なので、同じ問題を何度解き直しても1回きり
        cafeMasteryTasks.add(taskKey);
        cafeMasteryDayTasks.add(taskKey);
        // 自動売上の枠だけは毎回1問分戻す。完走後は★が増えないので、ここが自動営業設備を
        // 動かし続ける唯一の入口になる。何度復習しても枠は既定の5問分より広がらず、
        // 回収はレート（最上位でも5%/分）で律速されるので、1問分でも20分かかる。
        cafeReviewPassiveCredits =
                Math.min(MAX_REVIEW_PASSIVE_CREDITS, cafeReviewPassiveCredits + 1);
        refreshCafeAchievements();
        saveSoon();
    }

    /**
     * 復習の予定を、提出の結果に応じて進める。
     *
     * <p>「実行」＝「採点」なので、1問を仕上げるまでに何度も失敗が届く。そのため
     * <b>期限を動かすのは正解したときだけ</b>にしてある。失敗では日付を触らず、
     * 「その日に失敗した」という印だけを残す。こうすると:</p>
     *
     * <ul>
     *   <li>すっと通れば次のレベルへ（間隔が伸びて、しばらく出てこない）</li>
     *   <li>同じ日に失敗してから通したら1つ戻す（危なかったので早めにまた出す）</li>
     *   <li>失敗したまま諦めたら期限は動かない ― 期限切れのまま残るので、次も出てくる</li>
     * </ul>
     *
     * @return 記録が変わったら true
     */
    private boolean updateReviewPlan(String taskKey, boolean passed) {
        String today = LocalDate.now().toString();
        ReviewPlan current = reviewPlans.get(taskKey);
        if (!passed) {
            // 期限は動かさない。通せていないのだから、また出てくるのが正しい
            ReviewPlan base = current == null
                    ? new ReviewPlan(0, clearedDate(taskKey), "")
                    : current;
            if (today.equals(base.lastFailAt())) {
                return false;
            }
            reviewPlans.put(taskKey, new ReviewPlan(base.level(), base.lastAt(), today));
            return true;
        }
        if (!cleared.containsKey(taskKey)) {
            return false;
        }
        int level = current == null ? 0 : current.level();
        boolean stumbled = current != null && today.equals(current.lastFailAt());
        int next = stumbled
                ? Math.max(0, level - 1)
                : Math.min(REVIEW_INTERVAL_DAYS.length - 1, level + 1);
        reviewPlans.put(taskKey, new ReviewPlan(next, today, ""));
        return true;
    }

    /** その問題を初クリアした日。分からなければ今日。復習予定の起点に使う。 */
    private String clearedDate(String taskKey) {
        Cleared c = cleared.get(taskKey);
        return c == null ? LocalDate.now().toString() : c.clearedAt();
    }

    /**
     * その問題を次に確認すべき日。
     *
     * <p>まだ一度も復習していない問題は「初クリアの翌日」が最初の期限になる。
     * 期限日そのものは保存せず、最後の復習日とレベルから毎回引き直す ―
     * {@link #REVIEW_INTERVAL_DAYS} を調整したら過去の記録にもそのまま効く。</p>
     */
    public synchronized ReviewDue reviewDue(String taskKey) {
        ReviewPlan plan = reviewPlans.get(taskKey);
        int level = plan == null ? 0 : Math.min(plan.level(), REVIEW_INTERVAL_DAYS.length - 1);
        String from = plan == null || plan.lastAt().isEmpty()
                ? clearedDate(taskKey)
                : plan.lastAt();
        LocalDate base;
        try {
            base = LocalDate.parse(from);
        } catch (RuntimeException e) {
            base = LocalDate.now();
        }
        LocalDate due = base.plusDays(REVIEW_INTERVAL_DAYS[level]);
        long days = ChronoUnit.DAYS.between(LocalDate.now(), due);
        return new ReviewDue(level, due.toString(),
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days)));
    }

    /**
     * 苦手度を上下させ、値が変わったら true を返す。単位は {@link #REVIEW_WEIGHT_SCALE} 刻み。
     *
     * 0と上限で頭打ちにする。0のときは記録を消す（既定値なので、進捗ファイルに
     * 「間違えていない問題」を並べても意味がない）。
     */
    private boolean addReviewWeight(String taskKey, int delta) {
        int current = reviewWeight.getOrDefault(taskKey, 0);
        int next = Math.max(0, Math.min(MAX_REVIEW_WEIGHT, current + delta));
        if (next == current) {
            return false;
        }
        if (next == 0) {
            reviewWeight.remove(taskKey);
        } else {
            reviewWeight.put(taskKey, next);
        }
        return true;
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
        for (Map.Entry<String, Cleared> entry : cleared.entrySet()) {
            Cleared c = entry.getValue();
            if (c.hintsUsed() == 0 && c.attempts() <= 1) {
                flawlessRun++;
                bestFlawlessRun = Math.max(bestFlawlessRun, flawlessRun);
            } else {
                flawlessRun = 0;
            }
            if (Math.max(c.attempts(), attempts.getOrDefault(entry.getKey(), 0))
                    >= RETRY_ACHIEVEMENT_ATTEMPTS) {
                persistent = true;
            }
            clearsPerDay.merge(c.clearedAt(), 1, Integer::sum);
        }
        int busiestDay = 0;
        for (int count : clearsPerDay.values()) {
            busiestDay = Math.max(busiestDay, count);
        }
        if (LocalDate.now().toString().equals(cafeMasteryDay)) {
            Set<String> todayTasks = new LinkedHashSet<>(cafeMasteryDayTasks);
            for (Map.Entry<String, Cleared> entry : cleared.entrySet()) {
                if (cafeMasteryDay.equals(entry.getValue().clearedAt())) {
                    todayTasks.add(entry.getKey());
                }
            }
            busiestDay = Math.max(busiestDay, todayTasks.size());
        }
        boolean changed = award("same_day_15", busiestDay >= 15);
        changed |= award("streak_7", longestClearStreak() >= 7);
        changed |= award("quiz_streak_20",
                cafeQuizFirstStreak >= 20 || cafeQuizMasteryRun.size() >= 20);
        changed |= award("store_5", cafeStores >= 5);
        changed |= award("persistent_clear", persistent);
        // 重い2つ。復習ノートは全574問の3分の1以上、トロフィーは25問連続の無傷クリア
        changed |= award("review_200", cafeMasteryTasks.size() >= REVIEW_MASTERY_ITEM_TASKS);
        changed |= award("flawless_25",
                bestFlawlessRun >= FLAWLESS_ITEM_RUN
                        || cafeMasteryTaskRun.size() >= FLAWLESS_ITEM_RUN);
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
        boolean masteredInReview = cafeMasteryTasks.containsAll(chapterTaskKeys);
        boolean masteredToday = LocalDate.now().toString().equals(cafeMasteryDay)
                && cafeMasteryDayTasks.containsAll(chapterTaskKeys);
        boolean changed = award("chapter_no_hint", hintFree || masteredInReview);
        changed |= award("chapter_one_day", sameDay || masteredToday);
        if (changed) {
            saveSoon();
        }
    }

    private boolean award(String achievement, boolean reached) {
        return reached && cafeAchievements.add(achievement);
    }

    /**
     * 問題へ正解した1回ぶん、ラッキーコインの解放を抽選する。
     *
     * <p>種と抽選回数を保存するため、外れた直後に再起動しても同じ回を引き直せない。
     * いちど解放された後は抽選もカウントも止める。</p>
     *
     * @return 抽選回数または解放状態が変わったら true
     */
    private boolean drawLuckyCoinUnlock() {
        if (cafeAchievements.contains("lucky_coin_draw")) {
            return false;
        }
        cafeLuckyCoinUnlockDrawCount = saturatedAdd(cafeLuckyCoinUnlockDrawCount, 1L);
        if (isLuckyUnlockHit(cafeLuckyCoinUnlockSeed, cafeLuckyCoinUnlockDrawCount)) {
            cafeAchievements.add("lucky_coin_draw");
        }
        return true;
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

    /** 今日クリアした問題数。コンボスタンプ帳が積み上げる割合に使う。 */
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
        return isLuckyValue(value, chancePercent);
    }

    /** 利用者ごとの種を混ぜた、ラッキーコイン解放専用の1%抽選。 */
    private static boolean isLuckyUnlockHit(long seed, long sequence) {
        long value = seed ^ ((long) "lucky_coin_unlock".hashCode() << 32)
                ^ Long.rotateLeft(sequence * 0x9e3779b97f4a7c15L, 17);
        return isLuckyValue(value, LUCKY_COIN_UNLOCK_CHANCE_PERCENT);
    }

    /** 抽選ごとに作った値を十分に混ぜ、100個の確率枠へ割り当てる。 */
    private static boolean isLuckyValue(long value, int chancePercent) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return Long.remainderUnsigned(value, 100L) < chancePercent;
    }

    /**
     * その効果を持つアイテムを所持していれば返す。していなければ null。
     *
     * 1つのアイテムが複数の効果を持つので、効果名から引いてから
     * {@link CafeItem#effectValue(String)} でその効果の値を取る。
     */
    private CafeItem ownedItemWithEffect(String effectType) {
        for (CafeItem item : CAFE_ITEMS) {
            if (item.hasEffect(effectType) && cafeItems.contains(item.id())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 全報酬（問題・章・クイズ・自動売上）に掛かる唯一の売上%。
     *
     * <p>ここに足せる系統は販売戦略だけにする。以前は常連サービス系統も同じ和へ
     * 入れていたので、値段が同じで効果だけが弱い「劣化した販売戦略」になっていた。
     * 常連サービスは {@link #cafeDailyFirstBonusPercent()} が持つ別のスコープ
     * （その日の最初の1問だけ）へ移した。</p>
     */
    private int cafeSalesBonusPercent() {
        return cafeEffectTotal("sales");
    }

    /**
     * 常連サービス系統の効果。その日最初に初クリアした1問の報酬にだけ掛かる。
     *
     * 連続日数の数え方（下駄・上限）は皆勤の日めくり1枚が持つ。
     */
    private int cafeDailyFirstBonusPercent() {
        return cafeEffectTotal("streak") * effectiveStreakDays();
    }

    /** ボーナスに数える連続日数。長期離脱で差が開きすぎないよう既定は7日を上限にする。 */
    private int effectiveStreakDays() {
        CafeItem calendar = ownedItemWithEffect("streak_cap");
        int cap = calendar == null
                ? STREAK_BONUS_CAP_DAYS
                : Math.max(STREAK_BONUS_CAP_DAYS, calendar.effectValue("streak_cap"));
        return Math.min(streak(), cap);
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
        CafeItem truck = ownedItemWithEffect("store_bonus");
        long stores = truck == null ? cafeStores : cafeStores + truck.effectValue("store_bonus");
        return saturatedMultiply(cupsPerOrderWithUpgrades(), stores);
    }

    private long cafeCashForCups(long cups, CafeLearningProgress learning) {
        long baseCash = saturatedMultiply(cups, CUP_PRICE);
        long cashWithEquipment = applyPercent(baseCash, 100L + cafeSalesBonusPercent());
        return applyBasisPoints(cashWithEquipment, cafeBrandMultiplierBasisPoints(learning));
    }

    /**
     * ブランド倍率。初回クリアと復習の2つが育て、報酬すべてに掛かる。
     *
     * <p>初回は完成した章に含まれる問題数で加算し、短い章だけを先取りする攻略を防ぐ。
     * 復習ぶんは {@link #cafeReviewBrandBasisPoints()} が持つ。</p>
     */
    private long cafeBrandMultiplierBasisPoints(CafeLearningProgress learning) {
        long growth = saturatedMultiply(Math.max(0, learning.masteredChapterTasks()),
                BRAND_GROWTH_BASIS_POINTS_PER_TASK);
        long basisPoints = saturatedAdd(10_000L, growth);
        return saturatedAdd(basisPoints, cafeReviewBrandBasisPoints());
    }

    /**
     * 復習が育てたブランド倍率ぶん。
     *
     * <p>復習にコインは払わず、ここで倍率だけを育てる。1問につき1回しか数えないので
     * （集合で持っている）、解き直しを繰り返しても増えない。倍率は<b>これから</b>の
     * 報酬へ掛かるため、早く復習した人ほど得になる ―「間隔をあけて復習してほしい」
     * という教材側の狙いと、報酬の形が一致する。</p>
     *
     * <p>1問あたりの上限は復習ノートの4倍が乗って160で、初回クリアの170を超えない。
     * この順序は {@code tools/simulate-cafe.sh} が検査する。</p>
     */
    private long cafeReviewBrandBasisPoints() {
        long growth = saturatedMultiply(cafeMasteryTasks.size(),
                REVIEW_BRAND_GROWTH_BASIS_POINTS_PER_TASK);
        CafeItem note = ownedItemWithEffect("review_brand_multiplier");
        return note == null
                ? growth
                : saturatedMultiply(growth, note.effectValue("review_brand_multiplier"));
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

    /** 現在の★数で購入できる任意投資の最高段階。 */
    private int currentCafeInvestmentAvailableLevel() {
        int starsPastStart = cleared.size() - ENDGAME_INVESTMENT_START_STARS;
        return starsPastStart <= 0 ? 0 : starsPastStart / ENDGAME_INVESTMENT_STAR_INTERVAL;
    }

    private boolean cafeInvestmentVisible() {
        return cafeInvestmentLevel > 0
                || cleared.size() >= ENDGAME_INVESTMENT_START_STARS
                        + ENDGAME_INVESTMENT_STAR_INTERVAL;
    }

    private CafeInvestment nextCafeInvestment() {
        int level = cafeInvestmentLevel + 1;
        int requiredStars = ENDGAME_INVESTMENT_START_STARS
                + level * ENDGAME_INVESTMENT_STAR_INTERVAL;
        return new CafeInvestment(
                level,
                cafeInvestmentName(level),
                cafeInvestmentEmoji(level),
                cafeInvestmentDescription(level),
                requiredStars,
                cafeInvestmentCost(level));
    }

    private static long cafeInvestmentCost(int level) {
        long cost = ENDGAME_INVESTMENT_BASE_COST;
        for (int i = 1; i < level; i++) {
            cost = saturatedMultiply(cost, 2L);
        }
        return cost;
    }

    private static String cafeInvestmentName(int level) {
        return switch (level) {
            case 1 -> "フレームワーク認定ラウンジ";
            case 2 -> "運用管制センター";
            case 3 -> "Java Café記念館";
            default -> "Javaコミュニティ基金 第" + (level - 3) + "期";
        };
    }

    private static String cafeInvestmentEmoji(int level) {
        return switch (level) {
            case 1 -> "🏛️";
            case 2 -> "🛰️";
            case 3 -> "🏛️";
            default -> "🌱";
        };
    }

    private static String cafeInvestmentDescription(int level) {
        return switch (level) {
            case 1 -> "3製品の学びを称える認定ラウンジを開設します。";
            case 2 -> "世界の店舗を見守る運用・可観測性の拠点を作ります。";
            case 3 -> "積み重ねたJava学習を後世へ残す記念館を開設します。";
            default -> "次の学習者を支えるコミュニティ活動へ投資します。";
        };
    }

    private Map<String, Object> cafeInvestmentToClientJson(CafeInvestment investment) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("level", investment.level());
        value.put("name", investment.name());
        value.put("emoji", investment.emoji());
        value.put("description", investment.description());
        value.put("requiredStars", investment.requiredStars());
        value.put("cost", investment.cost());
        value.put("available", cleared.size() >= investment.requiredStars());
        value.put("completedLevel", cafeInvestmentLevel);
        value.put("availableLevel", currentCafeInvestmentAvailableLevel());
        value.put("rewardEffect", false);
        return value;
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
        CafeItem toolbox = ownedItemWithEffect("expansion_discount");
        return toolbox == null
                ? baseCost
                : discountedCost(baseCost, toolbox.effectValue("expansion_discount"));
    }

    private long cafeUpgradeCost(CafeUpgrade upgrade) {
        return discountedCost(upgrade.cost(), equipmentDiscountPercent());
    }

    private long cafeAutomationCost(CafeAutomation automation) {
        return discountedCost(automation.cost(), equipmentDiscountPercent());
    }

    /** 設備費の割引率。マイスター工具箱を持っていれば20%。 */
    private int equipmentDiscountPercent() {
        CafeItem toolbox = ownedItemWithEffect("equipment_discount");
        return toolbox == null ? 0 : toolbox.effectValue("equipment_discount");
    }

    /** クリア済みの問題のうち、復習で仕上げた割合（0〜100）。画面表示に使う。 */
    private int reviewedTaskPercent() {
        if (cleared.isEmpty()) {
            return 0;
        }
        int reviewed = 0;
        for (String key : cafeMasteryTasks) {
            if (cleared.containsKey(key)) {
                reviewed++;
            }
        }
        return Math.min(100, reviewed * 100 / cleared.size());
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
    private long cafePassiveCashPerMinute(CafeLearningProgress learning) {
        CafeAutomation automation = currentCafeAutomation();
        if (automation == null) {
            return 0;
        }
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        return Math.max(1L, applyBasisPoints(taskCash, automation.rateBasisPointsPerMinute()));
    }

    private long cafePassiveCashCap(CafeLearningProgress learning) {
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
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
        // 初クリア時の回数だけでなく、クリア後に粘って復習した回数も達成条件に含める。
        refreshCafeAchievements();
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

    /**
     * ブックマークを付け外しして、切り替え後の状態を返す。
     *
     * クリア前の問題にも付けられる（気になった問題を後で見直せるように）。
     * 復習モードの一覧に出るのはクリア済みの問題だけ。
     */
    public synchronized boolean toggleBookmark(String taskKey) {
        boolean bookmarked = !bookmarks.remove(taskKey);
        if (bookmarked) {
            bookmarks.add(taskKey);
        }
        saveSoon();
        return bookmarked;
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
     * 初回答の連続記録は従来どおり残す。取り逃した場合は、答え直しを含む
     * 重複しない20問の連続正解でも復習達成できる。同じクイズの連打では増えない。
     */
    public synchronized void recordQuiz(String lessonId, int index, int choice, boolean correct) {
        String key = quizKey(lessonId, index);
        if (!quizChoices.containsKey(key)) {
            cafeQuizFirstStreak = correct ? cafeQuizFirstStreak + 1 : 0;
        }
        if (correct) {
            cafeQuizMasteryRun.add(key);
        } else {
            cafeQuizMasteryRun.clear();
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

    /** オンボーディング完了を記録する。何度呼ばれても状態は変わらない。 */
    public synchronized void completeOnboarding() {
        if (onboardingCompleted) {
            return;
        }
        onboardingCompleted = true;
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
        onboardingCompleted = false;
        cleared.clear();
        codes.clear();
        hintsRevealed.clear();
        attempts.clear();
        bestPassed.clear();
        quizChoices.clear();
        clearDates.clear();
        reviewWeight.clear();
        bookmarks.clear();
        reviewPlans.clear();
        cafeCash = 0;
        cafeCups = 0;
        cafeLifetimeCash = 0;
        cafeRewardSequence = 0;
        cafeLuckyCoinUnlockSeed = ThreadLocalRandom.current().nextLong();
        cafeLuckyCoinUnlockDrawCount = 0;
        cafeTaskRewardCount = 0;
        cafePassiveCashSinceTask = 0;
        cafeReviewPassiveCredits = 0;
        cafeDailyFirstRewardDay = "";
        cafeStores = 1;
        cafeInvestmentLevel = 0;
        cafeUpgrades.clear();
        cafeAutomationUpgrades.clear();
        cafeItems.clear();
        cafeSeenItems.clear();
        cafeAchievements.clear();
        cafeQuizFirstStreak = 0;
        cafeMasteryTaskRun.clear();
        cafeMasteryTasks.clear();
        cafeMasteryDay = "";
        cafeMasteryDayTasks.clear();
        cafeQuizMasteryRun.clear();
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
            onboardingCompleted = root.get("onboardingCompleted") instanceof Boolean completed
                    && completed;

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
            // 目盛りを細かくする前のファイルは1点=1で入っている。4倍して読み替える
            int weightScale = MiniJson.intOf(root, "reviewWeightScale", 1);
            int weightFactor = weightScale >= REVIEW_WEIGHT_SCALE
                    ? 1
                    : REVIEW_WEIGHT_SCALE / Math.max(1, weightScale);
            MiniJson.obj(root, "reviewWeight").forEach((id, v) -> {
                if (v instanceof Number n) {
                    int weight = Math.min(MAX_REVIEW_WEIGHT,
                            Math.max(0, n.intValue() * weightFactor));
                    if (weight > 0) {
                        reviewWeight.put(migrateKey(id), weight);
                    }
                }
            });
            MiniJson.obj(root, "reviewPlans").forEach((id, v) -> {
                Map<String, Object> plan = MiniJson.asObj(v);
                int level = Math.max(0, Math.min(REVIEW_INTERVAL_DAYS.length - 1,
                        MiniJson.intOf(plan, "level", 0)));
                String lastAt = MiniJson.str(plan, "at", "");
                String lastFailAt = MiniJson.str(plan, "failAt", "");
                if (!lastAt.isEmpty() && !isDate(lastAt)) {
                    lastAt = "";
                }
                if (!lastFailAt.isEmpty() && !isDate(lastFailAt)) {
                    lastFailAt = "";
                }
                reviewPlans.put(migrateKey(id), new ReviewPlan(level, lastAt, lastFailAt));
            });
            for (Object o : MiniJson.list(root, "bookmarks")) {
                if (o instanceof String s) {
                    bookmarks.add(migrateKey(s));
                }
            }
            if (!root.containsKey("reviewWeight")) {
                seedReviewWeightFromAttempts();
            }

            if (hasCafeState) {
                Map<String, Object> cafe = MiniJson.obj(root, "cafe");
                cafeCash = longOf(cafe, "cash", 0);
                cafeCups = longOf(cafe, "cups", 0);
                cafeLifetimeCash = cafe.containsKey("lifetimeCash")
                        ? longOf(cafe, "lifetimeCash", cafeCash)
                        : Math.max(cafeCash, saturatedMultiply(cafeCups, CUP_PRICE));
                cafeRewardSequence = longOf(cafe, "rewardSequence", 0);
                cafeLuckyCoinUnlockSeed = longOf(
                        cafe, "luckyCoinUnlockSeed", cafeLuckyCoinUnlockSeed);
                cafeLuckyCoinUnlockDrawCount = Math.max(
                        0L, longOf(cafe, "luckyCoinUnlockDrawCount", 0));
                cafeTaskRewardCount = longOf(cafe, "taskRewardCount", cleared.size());
                cafePassiveCashSinceTask = longOf(cafe, "passiveCashSinceTask", 0);
                cafeReviewPassiveCredits = Math.min(MAX_REVIEW_PASSIVE_CREDITS,
                        Math.max(0, MiniJson.intOf(cafe, "reviewPassiveCredits", 0)));
                cafeDailyFirstRewardDay = MiniJson.str(cafe, "dailyFirstRewardDay", "");
                cafeStores = Math.min(MAX_CAFE_STORES,
                        Math.max(1, MiniJson.intOf(cafe, "storeCount", 1)));
                cafeInvestmentLevel = Math.min(1_000,
                        Math.max(0, MiniJson.intOf(cafe, "investmentLevel", 0)));
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
                for (Object o : MiniJson.list(cafe, "masteryTaskRun")) {
                    if (o instanceof String s) {
                        cafeMasteryTaskRun.add(migrateKey(s));
                    }
                }
                for (Object o : MiniJson.list(cafe, "masteryTasks")) {
                    if (o instanceof String s) {
                        cafeMasteryTasks.add(migrateKey(s));
                    }
                }
                cafeMasteryDay = MiniJson.str(cafe, "masteryDay", "");
                for (Object o : MiniJson.list(cafe, "masteryDayTasks")) {
                    if (o instanceof String s) {
                        cafeMasteryDayTasks.add(migrateKey(s));
                    }
                }
                for (Object o : MiniJson.list(cafe, "quizMasteryRun")) {
                    if (o instanceof String s) {
                        cafeQuizMasteryRun.add(s);
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
                cafeLifetimeCash = cafeCash;
                cafeTaskRewardCount = cleared.size();
            }
            // フラグ導入前のセーブでも学習履歴があれば既存利用者として扱う。
            onboardingCompleted = onboardingCompleted || hasLearningProgress();
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
     * 復習モードより前の進捗ファイルに、これまでの提出回数から苦手度を作る。
     *
     * 苦手度を記録していなかった頃のファイルには「どの問題で間違えたか」が残っていないが、
     * 初クリアまでの提出回数（{@link Cleared#attempts()}）は分かる。2回以上かかった問題は
     * 少なくともその回数ぶん間違えているので、それを初期値にする。こうしないと、
     * これまで解いてきた人の復習が全問同じ頻度から始まり、苦手な問題が埋もれてしまう。
     */
    private void seedReviewWeightFromAttempts() {
        cleared.forEach((key, c) -> {
            // 当時の1回は採点1回ぶんなので、新しい目盛りではそのまま1単位で数える
            int misses = Math.min(MAX_REVIEW_WEIGHT, c.attempts() - 1);
            if (misses > 0) {
                reviewWeight.put(key, misses);
            }
        });
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
        m.put("onboardingCompleted", onboardingCompleted);
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
        m.put("reviewWeightScale", REVIEW_WEIGHT_SCALE);
        m.put("reviewWeight", new LinkedHashMap<>(reviewWeight));
        Map<String, Object> plans = new LinkedHashMap<>();
        reviewPlans.forEach((key, plan) -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("level", plan.level());
            pm.put("at", plan.lastAt());
            pm.put("failAt", plan.lastFailAt());
            plans.put(key, pm);
        });
        m.put("reviewPlans", plans);
        m.put("bookmarks", new ArrayList<>(bookmarks));

        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("economyVersion", CAFE_ECONOMY_VERSION);
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("lifetimeCash", cafeLifetimeCash);
        cafe.put("rewardSequence", cafeRewardSequence);
        cafe.put("luckyCoinUnlockSeed", cafeLuckyCoinUnlockSeed);
        cafe.put("luckyCoinUnlockDrawCount", cafeLuckyCoinUnlockDrawCount);
        cafe.put("taskRewardCount", cafeTaskRewardCount);
        cafe.put("passiveCashSinceTask", cafePassiveCashSinceTask);
        cafe.put("reviewPassiveCredits", cafeReviewPassiveCredits);
        cafe.put("dailyFirstRewardDay", cafeDailyFirstRewardDay);
        cafe.put("storeCount", cafeStores);
        cafe.put("investmentLevel", cafeInvestmentLevel);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("ownedAutomation", new ArrayList<>(cafeAutomationUpgrades));
        cafe.put("ownedItems", new ArrayList<>(cafeItems));
        cafe.put("seenItems", new ArrayList<>(cafeSeenItems));
        cafe.put("achievements", new ArrayList<>(cafeAchievements));
        cafe.put("quizFirstStreak", cafeQuizFirstStreak);
        cafe.put("masteryTaskRun", new ArrayList<>(cafeMasteryTaskRun));
        cafe.put("masteryTasks", new ArrayList<>(cafeMasteryTasks));
        cafe.put("masteryDay", cafeMasteryDay);
        cafe.put("masteryDayTasks", new ArrayList<>(cafeMasteryDayTasks));
        cafe.put("quizMasteryRun", new ArrayList<>(cafeQuizMasteryRun));
        cafe.put("rewardedQuizzes", new ArrayList<>(rewardedQuizzes));
        cafe.put("rewardedChapters", new ArrayList<>(rewardedChapters));
        m.put("cafe", cafe);
        return m;
    }

    /** カフェの状態を除く、学習そのものの保存データが存在するか。 */
    private boolean hasLearningProgress() {
        return !cleared.isEmpty()
                || !codes.isEmpty()
                || !hintsRevealed.isEmpty()
                || !attempts.isEmpty()
                || !bestPassed.isEmpty()
                || !quizChoices.isEmpty()
                || !clearDates.isEmpty()
                || !reviewWeight.isEmpty()
                || !bookmarks.isEmpty()
                || !reviewPlans.isEmpty();
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
