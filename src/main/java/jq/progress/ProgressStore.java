package jq.progress;

import jq.content.Lesson;
import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 *  - クイズのしおり（復習ホームの一覧に出すだけ。出題順には関わらない）
 *  - Java Café の状態（売上・設備・アイテムなど。規則は {@link CafeEconomy} が持つ）
 *
 * 1レッスンに練習問題が複数あるので、★もコードもヒントも **問題ごと** に持つ。
 * キーは {@code レッスンID#連番}（{@link jq.content.Lesson#taskKey}）。
 * クイズだけはレッスン単位なので {@code レッスンID#クイズ番号} を別のマップに持つ。
 *
 * サーバは複数リクエストを並行に処理するので、状態変更は全て synchronized で守る。
 *
 * ただし錠が効くのは1つのプロセスの中だけで、<b>同じファイルを見るサーバが2つ動くと
 * 後から書いた側の写しが勝つ</b>（保存がファイル全体の書き直しなので混ぜ合わせようがない）。
 * そちらは {@link ProgressLock} が防ぐ ―― 本番の入口（{@code jq.App}）は
 * このクラスを作る前に錠を取る。
 *
 * 保存はファイル全体の書き直しになるため、変更のたびには書かない。
 * {@link #saveSoon()}（★や購入など）と {@link #saveEventually()}（自動売上のtick）で
 * 溜めて、{@link #flushNow()} がまとめて1回書く。終了時は {@code jq.App} の
 * シャットダウンフックが最後に {@link #flushNow()} を呼ぶ。
 */
public final class ProgressStore {

    /**
     * 苦手度の目盛り。1点ぶんを何単位で数えるか。
     *
     * <p>内部を4倍の細かさで持つのは、失敗1回で1点上がると振り切れてしまうため。
     * 進捗ファイルにもこの目盛りで保存する（{@code reviewWeightScale}）ので、
     * <b>この値を変えると過去の記録を読み込み時に換算する必要がある</b>
     * （{@link #loadFrom} の {@code weightFactor}）。1回ぶんの重みを変えたいだけなら
     * {@link #REVIEW_WEIGHT_PER_FAIL} を動かす ―― あちらは換算が要らない。</p>
     */
    private static final int REVIEW_WEIGHT_SCALE = 4;

    /**
     * 提出が通らなかった1回で上がる苦手度（単位）。<b>2単位 = 0.5点</b>。
     *
     * <p>2026-08-12〜19に「▶ 実行して採点」の1つへ畳んでいたころは<b>1単位（0.25点）</b>
     * だった ―― 書いている途中の試行錯誤まで採点として届くので、1回1点では振り切れたため。
     * 8/19に「▶ 試しに実行」（採点なし・{@code /api/run}）へ分けたあとは、
     * <b>試行錯誤があちらへ移った</b>。ここへ届く1回は「できたと思って出したのに違った」
     * という強い1回なので、0.25点では軽すぎた ―― 実際の記録では63問を解いて
     * 苦手度が残ったのが3問・合計0.75点しかなく、画面が「苦手な問題はありません」と
     * 出ていた（2026-08-22・利用者の指摘）。</p>
     *
     * <p>0.5点にすると、失敗 1 / 3 / 6 回でバッジが
     * `もう一度` / `🔥 苦手` / `🔥 よく間違えた` へ変わる（しきい値は
     * {@code web/app.js} の {@code reviewWeightLevel}）。<b>片方だけ動かすと表示がずれる。</b>
     * 復習で正解したときに下がるのは1点（= 失敗2回ぶん）で、そちらは変えていない。</p>
     */
    private static final int REVIEW_WEIGHT_PER_FAIL = REVIEW_WEIGHT_SCALE / 2;

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
     * <p>期限は「最後に復習した日 + この間隔」で決まるので、ここを変えれば過去の記録にも
     * 新しい間隔がそのまま効く（期限日は保存しない）。</p>
     *
     * <p>上がり方は一定ではない。<b>できている問題は早く抜ける</b>ようにしてあり、
     * 詰まった問題にだけ回数を使う（→ {@link #updateReviewPlan}・
     * {@link #initialReviewLevel}）。最後まで進むと4か月ごとの確認になる。</p>
     */
    private static final int[] REVIEW_INTERVAL_DAYS = {1, 3, 7, 14, 30, 60, 120};

    /** いちばん間隔の空いた段。「もう理解した」（{@link #easeTaskReview}）が飛ばす先。 */
    private static final int MAX_REVIEW_LEVEL = REVIEW_INTERVAL_DAYS.length - 1;

    /**
     * 何回続けて「一発正解」したら間隔を飛ばすか（2026-08-19・利用者の指示で2連続）。
     *
     * <p>1回だけでは足りない。たまたま覚えていた日と、間隔を空けても残っていた日は
     * 区別したいので、<b>別の日に2回</b>すっと通ったことを条件にしてある
     * （同じ日に2回通しても、失敗していなければ2連続と数える点は割り切り ―
     * 期限が来ていない問題を続けて解き直す人を止める理由はない）。</p>
     */
    private static final int CLEAN_RUN_FOR_SKIP = 2;

    /**
     * 飛ばすときに進める段数。
     *
     * <p>1段ずつだと 1→3→7→14→30→60→120日 と<b>6回</b>解き直すまで長い間隔にならず、
     * できている問題にも復習の枠を使い続けることになる。2段にすると、一発正解が続く問題は
     * 3〜4回で4か月ごとへ抜ける。間違えれば連続は切れて1段ずつに戻るので、
     * 危ない問題が飛ばされることはない。</p>
     */
    private static final int REVIEW_LEVEL_SKIP = 2;

    /**
     * 一発正解の連続を数える上限。判定に使うのは {@link #CLEAN_RUN_FOR_SKIP} までだが、
     * 画面が「どれくらい安定しているか」を出せるよう、少しだけ先まで数える。
     */
    private static final int MAX_CLEAN_RUN = 9;

    /**
     * ★の獲得や購入のような「失うと痛い変更」を書き出すまでの待ち時間。
     *
     * 保存はファイル全体の書き直しなので、変更のたびに書くと自動保存（打鍵0.8秒後）が
     * そのままディスク書き込みになる。少しだけ待ってまとめると、続けて起きる変更が
     * 1回の書き込みに畳まれる。
     */
    private static final long SAVE_DELAY_MS = 1_000L;

    /**
     * 進捗ファイルはJSONとして読めたのに、中身を取り込む途中で落ちた。
     *
     * <p>つまり<b>利用者の記録は無事で、落ちたのはこちら側</b>という状態である。
     * 消して作り直すのは間違いなので、ファイルに手を付けずにこれを投げ、
     * {@code jq.App} が案内を出して起動を諦める。</p>
     *
     * <p>これが出るのは版を上げた直後がほとんどで、直し方は
     * 「前の版に戻す」か「不具合を直す」のどちらか。どちらにしても進捗は残っている。</p>
     */
    public static final class LoadFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Path file;
        private final transient Path suggestedBackup;

        LoadFailedException(Path file, Path suggestedBackup, RuntimeException cause) {
            super("進捗ファイルを取り込めませんでした: " + file, cause);
            this.file = file;
            this.suggestedBackup = suggestedBackup;
        }

        /** 読めなかった進捗ファイル。 */
        public Path file() {
            return file;
        }

        /**
         * 「進捗を捨ててでも起動したい」人へ案内する退避先。
         *
         * <p>{@link ProgressStore#nextBackupPath(String)} で選んでいるので、
         * すでにある控えを潰さない名前になっている。</p>
         */
        public Path suggestedBackup() {
            return suggestedBackup;
        }
    }

    /**
     * 控えを取っておく数の上限（{@link #nextBackupPath(String)}）。
     *
     * ここまで埋まったら、いちばん新しい番号を上書きする。
     * 番号なしのもの（＝最初に取った控え）は上書きしない。
     */
    private static final int MAX_BACKUPS = 20;

    /** 読めなかった進捗ファイルの控えに付ける名前。 */
    private static final String BROKEN_SUFFIX = ".broken";

    /** 利用者がリセットする直前の控えに付ける名前。 */
    private static final String BEFORE_RESET_SUFFIX = ".before-reset";

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

    /**
     * 書き出しを永久に止める札。読み込みが途中で落ちたときに立てる（{@link #load()}）。
     *
     * <p>半端に読めた状態を書き戻すと、読めなかった残りを<b>本当に</b>失う。
     * {@link LoadFailedException} を投げるので普通は誰も使い続けないが、
     * 万一この store を握ったまま進まれても、ファイルは潰さない。</p>
     */
    private boolean writeDisabled;

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
    /**
     * 章ごとの層（概念／コード／実践）を最初に達成した日。キーは {@code 章ID#層}。
     *
     * <p>層の達成は進捗から導けるが、<b>導出だけにすると章へ問題が増えた瞬間に
     * 過去の達成が未達成へ戻る</b>。それでは「この章の実践までやり切った」という記録として
     * 使えない。カフェの所有アイテムと同じく、一度達成したら消さない。
     */
    private final Map<String, String> layerCompletions = new LinkedHashMap<>();
    /** 何かをクリアした日付 */
    private final Set<String> clearDates = new TreeSet<>();
    /**
     * 問題キー -> 苦手度（単位。0〜{@link #MAX_REVIEW_WEIGHT}）。復習の並び順に使う。
     *
     * 実行が通らないと1単位上がり、クリア済みの問題に正解すると1点（=4単位）下がる。
     * ここに載っていないことは「一度も間違えていない」を意味する。
     * 出題そのものを決めるのは苦手度ではなく復習の期限（{@link #reviewDue(String)}）で、
     * 苦手度は同じ期限のときにどちらを先に出すかを決めるだけ。
     */
    private final Map<String, Integer> reviewWeight = new LinkedHashMap<>();
    /** ブックマークした問題キー。復習モードで絞り込める。 */
    private final Set<String> bookmarks = new LinkedHashSet<>();
    /**
     * ブックマークした確認クイズのキー（{@link #quizKey}）。復習ホームの一覧に出す。
     *
     * <p><b>{@link #bookmarks} と分けてある。</b>クイズキー（{@code 5-2#1} = 2問目）は
     * 問題キー（{@code 5-2#1} = 問題1）と同じ形なので、同じ集合へ入れると
     * 「問題1」と「クイズ2問目」が同一視される。</p>
     *
     * <p>こちらは復習の出題順に少しだけ関わる（同じ期限のクイズを先に出す）。苦手度は
     * 持たない ― クイズは4択で、何回目で通ったかを測れないため。押すとそのクイズまで
     * 戻れる、しおりの役目が主である。</p>
     */
    private final Set<String> quizBookmarks = new LinkedHashSet<>();
    /**
     * 問題キー -> 復習の予定。忘却曲線でいつ確認するかを決める。
     *
     * 載っていない問題は「初クリアの翌日が期限」として扱う（{@link #reviewDue(String)}）。
     */
    private final Map<String, ReviewPlan> reviewPlans = new LinkedHashMap<>();
    /**
     * クイズキー（{@link #quizKey}） -> 復習の予定。問題と同じ忘却曲線で次に出す日を決める。
     *
     * <p>載っていないクイズは<b>今日が期限</b>として扱う（{@link #quizReviewDue}）。
     * クイズを答えた日は記録していないので、初回の起点はここしかない ― 先送りにすると、
     * この仕組みを入れた日から数日はクイズが1問も出ない画面になる。</p>
     *
     * <p>これが無かったころ、復習で「もう解いた」ことを覚えているのは📣の連続正解の集合
     * だけだった。1問間違えると集合が空に戻るので、そのたびに教材の先頭のクイズから
     * 出し直していた（2026-08-22・利用者の指摘）。</p>
     */
    private final Map<String, QuizPlan> quizPlans = new LinkedHashMap<>();
    /**
     * 「もう理解した」で間隔を飛ばす<b>直前</b>の予定。押した直後の「戻す」だけに使う。
     *
     * <p><b>保存しない・1つしか持たない。</b>戻せるのは押したその画面にいるあいだだけで
     * （次の問題へ進むとボタンごと消える）、それ以上さかのぼれる必要がない。ここを増やすと
     * 「いつまで戻せるのか」が画面から読めなくなる。</p>
     *
     * <p>予定が無かった問題を飛ばしたときは {@code plan} が null で入る（戻すときは
     * 予定そのものを消す ―― 「まだ復習していない」状態へ帰す）。</p>
     */
    private EasedBefore easedBefore = null;

    /**
     * Java Café の経済。売上・設備・アイテム・自動営業・店舗網・終盤投資の状態と規則。
     *
     * <p>学習の記録は {@link LearningView} 越しに読ませるだけで、書かせない。
     * 保存も自分ではせず、変更があったことだけをこちらへ知らせてくる。
     * カフェのメソッドはすべてこのクラスの {@code synchronized} メソッドから呼ぶので、
     * 錠は1つのままである。</p>
     */
    private final CafeEconomy cafe = new CafeEconomy(new LearningView(), new CafeEconomy.Saver() {
        @Override
        public void soon() {
            saveSoon();
        }

        @Override
        public void eventually() {
            saveEventually();
        }
    });

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
     * （学習の節目）で、値があれば {@link CafeCatalog#ACHIEVEMENT_NOTES} の達成条件で解放する。
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

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    /**
     * 1問の復習予定。
     *
     * @param level    どの間隔まで進んだか（{@link #REVIEW_INTERVAL_DAYS} の添字）
     * @param lastAt   最後に復習した日。ここに間隔を足したものが次の期限
     * @param lastFailAt 最後に失敗した日。同じ日に失敗してから通した正解は「危なかった」
     *                   とみなしてレベルを1つ戻すために持つ
     * @param cleanRun 失敗を挟まずに通した回数。{@link #CLEAN_RUN_FOR_SKIP} に届くと
     *                 間隔を飛ばす。失敗すると0へ戻る
     */
    public record ReviewPlan(int level, String lastAt, String lastFailAt, int cleanRun) {
    }

    /**
     * 確認クイズ1問の復習予定。
     *
     * <p>問題の {@link ReviewPlan} より短い。クイズは数秒で終わり、選択肢を選ぶだけなので
     * 苦手度も一発正解の連続も持たない ― 「どの間隔まで進んだか」と「最後に復習した日」で
     * 次の期限が決まる。間隔の表は問題と同じ {@link #REVIEW_INTERVAL_DAYS} を使う。</p>
     *
     * @param level  {@link #REVIEW_INTERVAL_DAYS} の添字
     * @param lastAt 最後に復習した日。ここに間隔を足したものが次の期限
     */
    public record QuizPlan(int level, String lastAt) {
    }

    /**
     * 「もう理解した」を押す直前の予定（{@link #easedBefore}）。
     *
     * @param quiz  クイズなら true、問題なら false。鍵の形（{@code 5-2#1}）が同じなので、
     *              どちらの予定なのかを持たないと取り違える
     * @param key   問題キーまたはクイズキー
     * @param task  問題の予定。予定が無かった問題なら null
     * @param quizPlan クイズの予定。予定が無かったクイズなら null
     */
    private record EasedBefore(boolean quiz, String key, ReviewPlan task, QuizPlan quizPlan) {
    }

    /**
     * 復習の期限。画面はこれを見て「期限切れ」「あと○日」を出す。
     *
     * @param cleanRun 一発正解の連続。画面が「定着している」と見せるために持たせている
     */
    public record ReviewDue(int level, String dueDate, int daysUntilDue, int cleanRun) {

        public boolean overdue() {
            return daysUntilDue <= 0;
        }

        /** いま間隔を飛ばす側にいるか（画面の印に使う）。 */
        public boolean onFastTrack() {
            return cleanRun >= CLEAN_RUN_FOR_SKIP;
        }
    }

    /**
     * 復習の提出で分かったこと。カフェの支払いを呼び分けるために返す。
     *
     * <p>どちらも <b>{@link #updateReviewPlan} が期限を書き換える前</b>にしか分からない。
     * 判定をここへ寄せているのは、期限の計算（{@link #reviewDue}）と同じ場所に置くためである。</p>
     *
     * @param duePassed   期限が来ていた問題を復習で通した（満額を払う条件）
     * @param earlyPassed 期限は来ていなかったが、復習で通した（「早めの復習」へ小額を払う条件。
     *                    同じ問題は1日1回・1日にN問までという上限は {@code CafeEconomy} が
     *                    見るので、ここでは「期限前だった」ことだけを返す）
     * @param cleanRecall その日に一度も失敗せず通した（思い出しのマドレーヌが見る）
     */
    public record ReviewOutcome(boolean duePassed, boolean earlyPassed, boolean cleanRecall) {

        public static final ReviewOutcome NONE = new ReviewOutcome(false, false, false);
    }

    public ProgressStore(Path file) {
        this.file = file;
        load();
        // saveEventually() で溜まったぶんを定期的に書き出す。変更が無い回は何もしない
        saver.scheduleWithFixedDelay(this::flushNow,
                TRICKLE_SAVE_INTERVAL_SEC, TRICKLE_SAVE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ read

    /**
     * 進捗の保存先。設定パネルに出すためのもので、書き込みには使わない
     * （書き出しは全てこのクラスの中で行う）。
     */
    public Path location() {
        return file;
    }

    /** 章の層を最初に達成した日（未達成なら null）。キーは {@code 章ID#層}。 */
    public synchronized String layerCompletedAt(String chapterId, String layerId) {
        return layerCompletions.get(chapterId + "#" + layerId);
    }

    /**
     * 章の層の達成を記録する。すでに記録があれば何もしない（達成日は最初のまま）。
     *
     * @return 新しく記録したら true
     */
    public synchronized boolean recordLayerCompletion(String chapterId, String layerId) {
        String key = chapterId + "#" + layerId;
        if (layerCompletions.containsKey(key)) {
            return false;
        }
        layerCompletions.put(key, LearningDay.todayText());
        saveSoon();
        return true;
    }

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

    /** そのクイズにしおりが付いているか。 */
    public synchronized boolean isQuizBookmarked(String lessonId, int index) {
        return quizBookmarks.contains(quizKey(lessonId, index));
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

    /**
     * 今日を含む連続学習日数。今日も昨日も学習していなければ 0。
     *
     * <p>「今日」は暦の日付ではなく学習日（{@link LearningDay}）である ―― 深夜0〜3時台に
     * 解いたぶんは前日として数えるので、寝る前の1問で連続が切れない。</p>
     */
    public synchronized int streak() {
        if (clearDates.isEmpty()) {
            return 0;
        }
        LocalDate today = LearningDay.today();
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
     * カフェへ渡す、学習の記録の読み取り窓（{@link LearningRecord}）。
     *
     * <p>公開メソッドを増やさずにカフェへ読ませるため、内側の実装として持つ。
     * 呼ばれるのは常に {@code ProgressStore} の {@code synchronized} メソッドの中
     * （カフェの処理はそこから呼ばれる）なので、ここで追加の同期はしない。</p>
     */
    private final class LearningView implements LearningRecord {

        @Override
        public int clearedTaskCount() {
            return cleared.size();
        }

        @Override
        public Cleared cleared(String taskKey) {
            return ProgressStore.this.cleared.get(taskKey);
        }

        @Override
        public int attempts(String taskKey) {
            return ProgressStore.this.attempts.getOrDefault(taskKey, 0);
        }

        @Override
        public int streakDays() {
            return streak();
        }

        @Override
        public int longestClearStreak() {
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

        /** 続きの数え方はクリアした順（{@code cleared} の並び）で見る。 */
        @Override
        public int bestFlawlessRun() {
            int run = 0;
            int best = 0;
            for (Cleared c : cleared.values()) {
                if (c.hintsUsed() == 0 && c.attempts() <= 1) {
                    run++;
                    best = Math.max(best, run);
                } else {
                    run = 0;
                }
            }
            return best;
        }

        @Override
        public int maxAttemptsOnAnyTask() {
            int most = 0;
            for (Map.Entry<String, Cleared> entry : cleared.entrySet()) {
                most = Math.max(most, Math.max(
                        entry.getValue().attempts(), attempts(entry.getKey())));
            }
            return most;
        }

        @Override
        public int busiestDayClears() {
            Map<String, Integer> clearsPerDay = new LinkedHashMap<>();
            for (Cleared c : cleared.values()) {
                clearsPerDay.merge(c.clearedAt(), 1, Integer::sum);
            }
            int busiest = 0;
            for (int count : clearsPerDay.values()) {
                busiest = Math.max(busiest, count);
            }
            return busiest;
        }

        @Override
        public Set<String> clearedKeys() {
            return new LinkedHashSet<>(cleared.keySet());
        }

        @Override
        public Set<String> clearedKeysOn(String day) {
            Set<String> keys = new LinkedHashSet<>();
            for (Map.Entry<String, Cleared> entry : cleared.entrySet()) {
                if (entry.getValue().clearedAt().equals(day)) {
                    keys.add(entry.getKey());
                }
            }
            return keys;
        }
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
        // 1日の区切り（時）。画面も「今日ぶん」を同じ境目で数える必要があるので、
        // 数字はここから渡す（両方に書くと片方だけ動いて食い違う → LearningDay）
        m.put("dayStartHour", LearningDay.START_HOUR);
        // 「もう理解した」で飛ぶ先の日数。ボタンの文面に出すので、数字は画面へ持たせない
        m.put("reviewEaseDays", REVIEW_INTERVAL_DAYS[MAX_REVIEW_LEVEL]);
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("cafe", cafe.toClientJson(learning));
        return m;
    }

    // ------------------------------------------------------------ カフェへの委譲
    //
    // 規則も状態も CafeEconomy にある。ここに残すのは窓口だけで、目的は2つ。
    //   ・呼び出し側（画面のAPIと検査ツール）から見た形を変えない
    //   ・カフェの処理を必ず synchronized の中で走らせ、錠を1つに保つ
    // 中身を読みたいときは CafeEconomy を開く。ここには何も書かない。

    /** 初クリアした問題の報酬。 */
    public synchronized CafeAward rewardTask(CafeLearningProgress learning, String taskKey) {
        return cafe.rewardTask(learning, taskKey);
    }

    /**
     * 期限が来た問題を復習で通したときの報酬。
     *
     * <p>払うかどうかは {@link #recordMasterySubmission} が返す {@link ReviewOutcome} で決まる。
     * 期限を書き換える前でないと判定できないため、判定と支払いを分けている。</p>
     */
    public synchronized CafeAward rewardReview(
            CafeLearningProgress learning, String taskKey, boolean cleanRecall) {
        return cafe.rewardReview(learning, taskKey, cleanRecall);
    }

    /**
     * 期限が来ていない問題（「早めの復習」）を通したときの報酬。
     *
     * <p>期限ぶんより小さい額で、上限が2つある ―― <b>同じ問題からは1日1回</b>と
     * <b>1日に払う本数</b>（どちらかに当たると {@link CafeAward#NONE}）。期限が上限を
     * 作らない側なので、その2つを {@code CafeEconomy} が日ごとに持っている。</p>
     */
    public synchronized CafeAward rewardEarlyReview(
            CafeLearningProgress learning, String taskKey, boolean cleanRecall) {
        return cafe.rewardEarlyReview(learning, taskKey, cleanRecall);
    }

    /** 章を初めて制覇したときのまとまったボーナス。 */
    public synchronized CafeAward rewardChapter(
            String chapterId, CafeLearningProgress learning, int chapterTaskCount) {
        return cafe.rewardChapter(chapterId, learning, chapterTaskCount);
    }

    // クイズのチップは回答の記録と同じできごとなので、窓口は recordQuiz の方にある。

    public synchronized PurchaseResult purchaseCafeUpgrade(String id) {
        return cafe.purchaseCafeUpgrade(id);
    }

    public synchronized AutomationPurchaseResult purchaseCafeAutomation(String id) {
        return cafe.purchaseCafeAutomation(id);
    }

    public synchronized PassiveSalesResult startCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        return cafe.startCafePassiveSales(sessionId, learning);
    }

    public synchronized PassiveSalesResult collectCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        return cafe.collectCafePassiveSales(sessionId, learning);
    }

    public synchronized PassiveSalesResult stopCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        return cafe.stopCafePassiveSales(sessionId, learning);
    }

    public synchronized ItemPurchaseResult purchaseCafeItem(String id) {
        return cafe.purchaseCafeItem(id);
    }

    public synchronized void markCafeItemsSeen() {
        cafe.markCafeItemsSeen();
    }

    public synchronized ExpansionResult expandCafeNetwork() {
        return cafe.expandCafeNetwork();
    }

    public synchronized InvestmentPurchaseResult purchaseCafeInvestment() {
        return cafe.purchaseCafeInvestment();
    }

    /**
     * 全問を終えてからカフェを開いた人へ、節目型アイテムの未所持分を贈る。
     *
     * @return 今回贈ったアイテム数。追加がなければ0
     */
    public synchronized int ensureCafeCompletionCatchUp(
            int currentCurriculumClearedTasks, int totalTaskCount) {
        return cafe.ensureCafeCompletionCatchUp(currentCurriculumClearedTasks, totalTaskCount);
    }

    /**
     * 章を全問クリアしたときだけ分かる達成条件を記録する。
     *
     * 章に属する問題キーは教材側しか知らないので、呼び出し側から渡してもらう。
     */
    public synchronized void noteChapterAchievements(List<String> chapterTaskKeys) {
        cafe.noteChapterAchievements(chapterTaskKeys);
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
     * カフェへ渡すのは「倍率」と「自動売上の枠」で、どちらも1問につき1回しか数えないため
     * 上限は問題数で構造的に決まる。<b>コインを払うかどうかもここで決める</b> ―
     * 期限が来ていた問題を復習で通したかは、下の {@link #updateReviewPlan} が期限を
     * 書き換える前にしか見られないので、その判定を {@link ReviewOutcome} として返し、
     * 支払いそのものは呼び出し側（{@code ApiHandler}）が {@code rewardReview} で行う。
     *
     * <p>この関数は {@code ApiHandler.doSubmit} で {@code markCleared} より<b>前</b>に
     * 呼ばれる。初クリアの時点ではまだ {@code cleared} に入っていないので、
     * 下の早期returnで抜ける ― つまり初クリアが復習ぶんの報酬を二重取りしない。
     * 順番を入れ替えるとこの前提が崩れる。</p>
     */
    public synchronized ReviewOutcome recordMasterySubmission(String taskKey, boolean passed) {
        return recordMasterySubmission(taskKey, passed, false);
    }

    /**
     * @param fromReview 復習モードからの提出なら true。<b>間隔の飛び級はこれだけで数える</b> ―
     *                   通常のレッスン画面ではクリアした自分の解答が最初から入っているので、
     *                   そのまま提出して通っても「思い出せた」ことにならない。復習は
     *                   ひな形から解き直すので、一発で通ったのなら覚えている
     */
    public synchronized ReviewOutcome recordMasterySubmission(String taskKey, boolean passed,
                                                              boolean fromReview) {
        // 期限が来ていたか・その日に失敗していないかは、updateReviewPlan が lastAt と
        // lastFailAt を書き換える前にしか見られない。先に控えておく
        ReviewPlan planBefore = reviewPlans.get(taskKey);
        boolean stumbled = planBefore != null
                && LearningDay.todayText().equals(planBefore.lastFailAt());
        boolean recalled = passed && fromReview && cleared.containsKey(taskKey);
        boolean overdue = recalled && reviewDue(taskKey).overdue();
        // 期限が来ていれば満額、来ていなければ「早めの復習」ぶん（1日の本数に上限あり）
        ReviewOutcome outcome = recalled
                ? new ReviewOutcome(overdue, !overdue, !stumbled)
                : ReviewOutcome.NONE;
        // 下げるのはクリア済みの問題に正解したときだけ。まだ通っていない問題で
        // 1ケースだけ通った提出などを「復習で正解」と数えないため。
        // 失敗は REVIEW_WEIGHT_PER_FAIL（2単位 = 0.5点）だけ上げる。試行錯誤は「試しに実行」が
        // 引き受けるので、ここへ届くのは「できたと思って出した」1回である。
        // 正解したときは1点（=4単位）まとめて下げる（＝失敗2回ぶん）。
        boolean changed = passed
                ? cleared.containsKey(taskKey) && addReviewWeight(taskKey, -REVIEW_WEIGHT_SCALE)
                : addReviewWeight(taskKey, REVIEW_WEIGHT_PER_FAIL);
        changed |= updateReviewPlan(taskKey, passed, fromReview);
        // カフェは「復習で通したか」だけを見る。★も報酬もここでは動かさない
        changed |= cafe.noteReviewSubmission(taskKey, passed, cleared.containsKey(taskKey));
        if (changed) {
            saveSoon();
        }
        return outcome;
    }

    /**
     * 復習の予定を、提出の結果に応じて進める。
     *
     * <p>提出＝採点なので、1問を仕上げるまでに何度も失敗が届く。そのため
     * <b>期限を動かすのは正解したときだけ</b>にしてある。失敗では日付を触らず、
     * 「その日に失敗した」という印だけを残す。こうすると:</p>
     *
     * <ul>
     *   <li>すっと通れば次のレベルへ（間隔が伸びて、しばらく出てこない）</li>
     *   <li><b>復習で失敗を挟まずに {@link #CLEAN_RUN_FOR_SKIP} 回続けて通したら
     *       {@link #REVIEW_LEVEL_SKIP} 段まとめて進める</b> ― できている問題を
     *       1段ずつ確認して回数を使い切らないため（2026-08-19）。数えるのは復習からの
     *       提出だけで、通常画面の再提出（解答が入っている）は1段のまま</li>
     *   <li>同じ日に失敗してから通したら1つ戻す（危なかったので早めにまた出す）。
     *       連続も切れるので、次に通しても飛び級はしない</li>
     *   <li>失敗したまま諦めたら期限は動かない ― 期限切れのまま残るので、次も出てくる</li>
     * </ul>
     *
     * @return 記録が変わったら true
     */
    private boolean updateReviewPlan(String taskKey, boolean passed, boolean fromReview) {
        String today = LearningDay.todayText();
        ReviewPlan current = reviewPlans.get(taskKey);
        if (!passed) {
            // 期限は動かさない。通せていないのだから、また出てくるのが正しい
            ReviewPlan base = current == null
                    ? new ReviewPlan(initialReviewLevel(taskKey), clearedDate(taskKey), "", 0)
                    : current;
            // 失敗した時点で一発正解の連続は切れる。同じ日に何度失敗しても記録は同じなので、
            // 印も連続も既にその形なら書かない（保存を無駄に呼ばないため）
            if (today.equals(base.lastFailAt()) && base.cleanRun() == 0) {
                return false;
            }
            reviewPlans.put(taskKey, new ReviewPlan(base.level(), base.lastAt(), today, 0));
            return true;
        }
        if (!cleared.containsKey(taskKey)) {
            return false;
        }
        int level = current == null ? initialReviewLevel(taskKey) : current.level();
        boolean stumbled = current != null && today.equals(current.lastFailAt());
        int before = current == null ? 0 : current.cleanRun();
        // 通常画面の再提出では連続を増やさない（増やさないだけで、減らしもしない）
        int cleanRun = stumbled ? 0
                : (fromReview ? Math.min(MAX_CLEAN_RUN, before + 1) : before);
        int step = fromReview && cleanRun >= CLEAN_RUN_FOR_SKIP ? REVIEW_LEVEL_SKIP : 1;
        int next = stumbled
                ? Math.max(0, level - 1)
                : Math.min(REVIEW_INTERVAL_DAYS.length - 1, level + step);
        reviewPlans.put(taskKey, new ReviewPlan(next, today, "", cleanRun));
        return true;
    }

    /**
     * まだ一度も復習していない問題の、最初のレベル。
     *
     * <p><b>ヒントを見ずに1回の提出でクリアした問題は、1段上（翌日ではなく3日後）から
     * 始める。</b>その場で書けた問題を翌日もう一度出すのは、復習の枠の使い方として重い。
     * ヒントを開いた・何度も提出した問題はこれまでどおり翌日に確認する。</p>
     *
     * <p>「試しに実行」は提出回数に入らないので、走らせて直してから1回で通した人も
     * ここに入る。採点を通さずに自分で直せたのなら、それも「書けた」でよい。</p>
     */
    private int initialReviewLevel(String taskKey) {
        Cleared c = cleared.get(taskKey);
        if (c == null) {
            return 0;
        }
        return c.hintsUsed() == 0 && c.attempts() <= 1 ? 1 : 0;
    }

    /** その問題を初クリアした日。分からなければ今日。復習予定の起点に使う。 */
    private String clearedDate(String taskKey) {
        Cleared c = cleared.get(taskKey);
        return c == null ? LearningDay.todayText() : c.clearedAt();
    }

    /**
     * その問題を次に確認すべき日。
     *
     * <p>まだ一度も復習していない問題は「初クリアの翌日」が最初の期限になる
     * （すっとクリアした問題だけ3日後 → {@link #initialReviewLevel}）。
     * 期限日そのものは保存せず、最後の復習日とレベルから毎回引き直す ―
     * {@link #REVIEW_INTERVAL_DAYS} を調整したら過去の記録にもそのまま効く。</p>
     */
    public synchronized ReviewDue reviewDue(String taskKey) {
        ReviewPlan plan = reviewPlans.get(taskKey);
        int level = plan == null
                ? initialReviewLevel(taskKey)
                : plan.level();
        String from = plan == null || plan.lastAt().isEmpty()
                ? clearedDate(taskKey)
                : plan.lastAt();
        return dueFrom(from, level, plan == null ? 0 : plan.cleanRun());
    }

    /**
     * 「最後に確認した日 + 間隔」から期限を引く。問題とクイズで同じ計算を使う。
     *
     * <p>期限日そのものは保存しないので、{@link #REVIEW_INTERVAL_DAYS} を調整すると
     * 過去の記録にもそのまま効く。</p>
     */
    private static ReviewDue dueFrom(String from, int level, int cleanRun) {
        int safe = Math.max(0, Math.min(level, REVIEW_INTERVAL_DAYS.length - 1));
        LocalDate base;
        try {
            base = LocalDate.parse(from);
        } catch (RuntimeException e) {
            base = LearningDay.today();
        }
        LocalDate due = base.plusDays(REVIEW_INTERVAL_DAYS[safe]);
        long days = ChronoUnit.DAYS.between(LearningDay.today(), due);
        return new ReviewDue(safe, due.toString(),
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days)), cleanRun);
    }

    /**
     * そのクイズを次に確認すべき日。画面はこれを見て復習に出すクイズを選ぶ。
     *
     * <p><b>まだ一度も復習していないクイズは「今日が期限」で返す。</b>答えた日を記録して
     * いないので、これしか起点が無い。</p>
     *
     * @param recordedCorrect いま記録に残っている回答が正解か（{@code quizChoices} の値と
     *                        教材の正解を突き合わせた結果 ― 教材を知らないここでは判定
     *                        できないので呼び出し側から渡す）。まだ復習していないクイズの
     *                        最初のレベルを決めるのに使う
     */
    public synchronized ReviewDue quizReviewDue(String lessonId, int index,
                                                boolean recordedCorrect) {
        QuizPlan plan = quizPlans.get(quizKey(lessonId, index));
        if (plan == null || plan.lastAt().isEmpty()) {
            return new ReviewDue(initialQuizLevel(recordedCorrect),
                    LearningDay.todayText(), 0, 0);
        }
        return dueFrom(plan.lastAt(), plan.level(), 0);
    }

    /**
     * 「この問題はもう理解した」。復習の間隔を<b>いちばん先まで</b>飛ばす（120日後）。
     *
     * <p>あまりに簡単な問題が何度も出てくるのが面倒、という声から足した（2026-08-22・
     * 利用者の依頼）。忘却曲線は「通した回数」で少しずつ間隔を伸ばすので、最初から書ける
     * 問題でも数回は付き合うことになる ―― その数回を1回で済ませるための操作である。</p>
     *
     * <p><b>取り返しはつく。</b>押した直後は {@link #undoEaseTaskReview} で戻せるし、
     * 期限前の問題も復習ホームの一覧から選んで解き直せる（解き直せば期限はまた動く）。
     * 苦手度も一発正解の連続もここでは触らない ―― 動かすのは「次に出す日」だけ。</p>
     *
     * @return 飛ばしたあとの期限。クリアしていない問題では null（予定を持てないため）
     */
    public synchronized ReviewDue easeTaskReview(String taskKey) {
        if (!cleared.containsKey(taskKey)) {
            return null;
        }
        ReviewPlan current = reviewPlans.get(taskKey);
        easedBefore = new EasedBefore(false, taskKey, current, null);
        int cleanRun = current == null ? 0 : current.cleanRun();
        reviewPlans.put(taskKey,
                new ReviewPlan(MAX_REVIEW_LEVEL, LearningDay.todayText(), "", cleanRun));
        saveSoon();
        return reviewDue(taskKey);
    }

    /**
     * 直前の「もう理解した」を取り消す（問題）。
     *
     * @return 戻したあとの期限。控えが無い・別の問題のものなら null（画面はその旨を出す）
     */
    public synchronized ReviewDue undoEaseTaskReview(String taskKey) {
        if (easedBefore == null || easedBefore.quiz() || !easedBefore.key().equals(taskKey)) {
            return null;
        }
        if (easedBefore.task() == null) {
            reviewPlans.remove(taskKey);
        } else {
            reviewPlans.put(taskKey, easedBefore.task());
        }
        easedBefore = null;
        saveSoon();
        return reviewDue(taskKey);
    }

    /**
     * 「このクイズはもう理解した」。問題と同じく間隔をいちばん先まで飛ばす。
     *
     * @param recordedCorrect いま記録に残っている回答が正解か（→ {@link #quizReviewDue}）
     */
    public synchronized ReviewDue easeQuizReview(String lessonId, int index,
                                                 boolean recordedCorrect) {
        String key = quizKey(lessonId, index);
        if (!quizChoices.containsKey(key)) {
            // 答えていないクイズは復習に出ないので、飛ばす意味も無い
            return null;
        }
        easedBefore = new EasedBefore(true, key, null, quizPlans.get(key));
        quizPlans.put(key, new QuizPlan(MAX_REVIEW_LEVEL, LearningDay.todayText()));
        saveSoon();
        return quizReviewDue(lessonId, index, recordedCorrect);
    }

    /** 直前の「もう理解した」を取り消す（クイズ）。 */
    public synchronized ReviewDue undoEaseQuizReview(String lessonId, int index,
                                                    boolean recordedCorrect) {
        String key = quizKey(lessonId, index);
        if (easedBefore == null || !easedBefore.quiz() || !easedBefore.key().equals(key)) {
            return null;
        }
        if (easedBefore.quizPlan() == null) {
            quizPlans.remove(key);
        } else {
            quizPlans.put(key, easedBefore.quizPlan());
        }
        easedBefore = null;
        saveSoon();
        return quizReviewDue(lessonId, index, recordedCorrect);
    }

    /**
     * まだ一度も復習していないクイズの、最初のレベル。
     *
     * <p>記録に残っている回答が正解のクイズは1段上から始める（復習で正解すると、翌日でも
     * 3日後でもなく<b>7日後</b>）。間違えたまま残っているクイズは0から ― 正解し直しても
     * 3日後にもう一度出す。問題側の {@link #initialReviewLevel} と同じ考えである。</p>
     */
    private static int initialQuizLevel(boolean recordedCorrect) {
        return recordedCorrect ? 1 : 0;
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
        cafe.refreshCafeAchievements();
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
        String today = LearningDay.todayText();
        if (isNew) {
            cleared.put(taskKey, new Cleared(
                    today,
                    hintsRevealed.getOrDefault(taskKey, 0),
                    attempts.getOrDefault(taskKey, 1)));
        }
        clearDates.add(today);
        cafe.refreshCafeAchievements();
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

    /**
     * 確認クイズのしおりを付け外しして、切り替え後の状態を返す。
     *
     * 答える前のクイズにも付けられる（気になった問いを後で見に行けるように）。
     * 復習の出題には関わらないので、期限も苦手度も動かさない。
     */
    public synchronized boolean toggleQuizBookmark(String lessonId, int index) {
        String key = quizKey(lessonId, index);
        boolean bookmarked = !quizBookmarks.remove(key);
        if (bookmarked) {
            quizBookmarks.add(key);
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
     * <p>何度でも答え直せるが、チップが出るのは<b>1度目の回答で正解したとき</b>だけ。
     * ここが持っているのは「何度目の回答か」だけで、いくら払うかはカフェが決める
     * （{@code CafeEconomy#noteQuizAnswered}）。</p>
     *
     * @return この回で払ったチップ。2度目以降の回答と不正解では {@link CafeAward#NONE}
     */
    public synchronized CafeAward recordQuiz(String lessonId, int index, int choice,
                                             boolean correct, CafeLearningProgress learning) {
        String key = quizKey(lessonId, index);
        boolean firstAnswer = !quizChoices.containsKey(key);
        quizChoices.put(key, choice);
        CafeAward award = cafe.noteQuizAnswered(key, correct, firstAnswer, learning);
        saveSoon();
        return award;
    }

    /**
     * 復習として出し直したクイズへの回答を記録する。
     *
     * <p>通常の回答（{@link #recordQuiz}）と分けてあるのは、<b>残すものが違う</b>ため。
     * ここでは {@code quizChoices} を書き換えない ― 書き換えると、復習で間違えただけで
     * 概念レッスンの★（最後に選んだ答えで数える）を失い、正解数の表示も減る。
     * チップも払わない（復習の原則）。動くのは2つだけで、「復習で連続正解したクイズ」
     * （📣ひらめきメガホンの解放条件）と、<b>そのクイズを次に出す日</b>である。</p>
     *
     * @param recordedCorrect いま記録に残っている回答が正解か（→ {@link #quizReviewDue}）
     */
    public synchronized void recordQuizReview(String lessonId, int index, boolean correct,
                                              boolean recordedCorrect) {
        String key = quizKey(lessonId, index);
        boolean changed = cafe.noteQuizReviewAnswered(key, correct);
        changed |= updateQuizPlan(key, correct, recordedCorrect);
        if (changed) {
            saveSoon();
        }
    }

    /**
     * クイズの復習予定を進める。正解で間隔を1段伸ばし、不正解で1段戻す。
     *
     * <p>問題側（{@link #updateReviewPlan}）と違って「同じ日に失敗してから通した」印は
     * 持たない。復習のクイズは答えた回でその段が終わるので、失敗してから通す経路が
     * そもそも無い。</p>
     *
     * <p><b>不正解でも日付は今日へ動かす。</b>間違えた直後は正解と解説を読んだところなので、
     * 同じ日にもう一度出すと「読んで押すだけ」になる（レベルは下がるので、翌日には戻ってくる）。</p>
     *
     * @return 記録が変わったら true（保存の予約に使う）
     */
    private boolean updateQuizPlan(String key, boolean correct, boolean recordedCorrect) {
        QuizPlan current = quizPlans.get(key);
        int level = current == null ? initialQuizLevel(recordedCorrect) : current.level();
        int next = correct
                ? Math.min(REVIEW_INTERVAL_DAYS.length - 1, level + 1)
                : Math.max(0, level - 1);
        String today = LearningDay.todayText();
        if (current != null && current.level() == next && today.equals(current.lastAt())) {
            return false;
        }
        quizPlans.put(key, new QuizPlan(next, today));
        return true;
    }

    /**
     * 進捗を全て消す。消す前に控えを1つ取る。
     *
     * <p>壊れたときは {@code .broken} が残るのに、<b>利用者が押したときは何も残らない</b>
     * 作りだった。設定パネルからワンクリック（確認1回）で、押し間違いが取り返せない。
     * 「取り返しのつかない要素を作らない」という他の作りと合わないので、
     * {@code progress.json.before-reset} へ控えを取ってから消す。</p>
     *
     * <p>控えを取る前に {@link #flushNow()} を通すのは、直前に取った★が
     * まだディスクに載っていないことがあるため（保存は1秒ためてから書く）。
     * 錠は {@code writeLock} → {@code this} の順で取る ―― この順を逆にしてはいけない。</p>
     *
     * <p>2度目のリセットでは {@code .before-reset.2} になる（{@link #nextBackupPath(String)}）。
     * 1度目に取った控え ―― 本物の記録が入っているほう ―― を潰さないため。</p>
     */
    public void resetAll() {
        synchronized (writeLock) {
            flushNow();                 // ディスクの内容を、いまの状態にそろえる
            copyBeforeReset();
        }
        synchronized (this) {
            clearAllState();
            saveSoon();
        }
    }

    /**
     * リセットの直前の進捗ファイルを控えへ写す。
     *
     * <p>{@code writeLock} を持った状態で呼ぶこと（書き出しと入れ違わないため）。
     * 写せなくてもリセットそのものは続ける ―― ここで止めると、
     * 「消したいのに消せない」という別の行き止まりになる。</p>
     */
    private void copyBeforeReset() {
        if (!Files.exists(file)) {
            return;     // まだ一度も保存していない。控えを取るものが無い
        }
        Path backup = nextBackupPath(BEFORE_RESET_SUFFIX);
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("進捗をリセットします。直前の状態は "
                    + backup.getFileName() + " に控えを取りました。");
        } catch (IOException e) {
            System.err.println("リセット前の控えを取れませんでした: " + e.getMessage());
        }
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
     * フィールドが足され、達成条件と連続正解数の
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
        layerCompletions.clear();
        clearDates.clear();
        reviewWeight.clear();
        bookmarks.clear();
        quizBookmarks.clear();
        reviewPlans.clear();
        quizPlans.clear();
        easedBefore = null;
        cafe.reset();
    }

    static String quizKey(String lessonId, int index) {
        return lessonId + "#" + index;
    }

    /**
     * 昔の進捗ファイルのキーを問題キーに読み替える。
     *
     * 1レッスン1問だった頃はレッスンIDそのものがキーだった（"5-2"）。
     * いまは問題ごとに "5-2#1" を使うので、"#" を含まない古いキーを1問目として扱う。
     * こうしないと、これまでの★が全部消えたように見えてしまう。
     */
    static String migrateKey(String key) {
        return key.contains("#") ? key : key + "#1";
    }

    /**
     * 概念レッスンへ変えたレッスンの、昔の★のキー。
     *
     * <p>提出課題が「問題文の表を写すだけ」だったレッスンを、解説と確認クイズだけの
     * 概念レッスンへ変えた（2026-08-17）。★のキーは {@code 50-4#1} から {@code 50-4#q} へ
     * 変わるので、読み替えないと**すでにクリアした人の★が消え、章クリアも外れる**。
     *
     * <p>読み替えるのは★（{@code cleared}）だけにする。書いたコードやヒントの開示数、
     * 復習の記録は、もう存在しない問題のものなので持ち込まない（画面はどこからも読まない）。
     *
     * <p>足し忘れは進捗が消えるまで表に出ないので、{@code tools/LayerCompletionCheck} が
     * この一覧を読み、教材側の概念レッスンがここか「最初から概念」の一覧のどちらかに
     * 入っていることを検査する（{@link #conceptMigratedTaskKeys()}）。
     */
    private static final Map<String, Integer> CONCEPT_MIGRATED_REQUIRED_TASK_COUNTS =
            Map.ofEntries(
                    Map.entry("21-5", 2),
                    Map.entry("27-2", 2),
                    Map.entry("37-1", 2),
                    Map.entry("37-5", 3),
                    Map.entry("45-5", 3),
                    Map.entry("46-1", 1),
                    Map.entry("46-2", 1),
                    Map.entry("46-3", 1),
                    Map.entry("46-4", 1),
                    Map.entry("48-1", 1),
                    Map.entry("48-2", 1),
                    Map.entry("50-1", 1),
                    Map.entry("50-3", 1),
                    Map.entry("50-4", 1),
                    Map.entry("50-5", 3),
                    Map.entry("51-1", 1),
                    Map.entry("51-2", 1),
                    Map.entry("51-4", 1),
                    Map.entry("51-5", 3),
                    Map.entry("52-1", 1),
                    Map.entry("52-3", 1),
                    Map.entry("52-4", 1),
                    Map.entry("52-6", 3),
                    Map.entry("52-7", 3),
                    Map.entry("53-1", 1),
                    Map.entry("53-3", 1),
                    Map.entry("53-4", 1),
                    Map.entry("53-5", 1),
                    Map.entry("55-3", 1),
                    Map.entry("58-5", 1),
                    Map.entry("60-3", 1),
                    Map.entry("60-4", 1),
                    Map.entry("61-5", 1),
                    Map.entry("62-4", 1),
                    Map.entry("62-6", 1));

    /** 読み替える旧キーの一覧。検査が自前の写しを持たないように公開する。 */
    public static Set<String> conceptMigratedTaskKeys() {
        Set<String> keys = new LinkedHashSet<>();
        CONCEPT_MIGRATED_REQUIRED_TASK_COUNTS.forEach((lessonId, count) -> {
            for (int number = 1; number <= count; number++) {
                keys.add(lessonId + "#" + number);
            }
        });
        return Set.copyOf(keys);
    }

    /**
     * 必須の実践課題を先頭へ移したときの、旧問題キー → 新問題キー。
     *
     * <p>問題キーは表示順の連番なので、単に並べ替えると昔の★・下書き・復習予定が
     * 別の問題へ付く。移した課題と一緒にすべての問題別記録を一度だけ読み替える。</p>
     */
    private record TaskMove(String id, Map<String, String> map) { }

    private static final List<TaskMove> TASK_MOVES = List.of(
            new TaskMove("practice-first-2026-09-04", Map.ofEntries(
                    Map.entry("37-2#1", "37-2#2"),
                    Map.entry("37-2#2", "37-2#1"),
                    Map.entry("37-3#1", "37-3#2"),
                    Map.entry("37-3#2", "37-3#1"),
                    Map.entry("45-4#1", "45-4#2"),
                    Map.entry("45-4#2", "45-4#1"),
                    Map.entry("46-5#1", "46-5#4"),
                    Map.entry("46-5#2", "46-5#2"),
                    Map.entry("46-5#3", "46-5#3"),
                    Map.entry("46-5#4", "46-5#1"),
                    Map.entry("48-5#1", "48-5#5"),
                    Map.entry("48-5#2", "48-5#3"),
                    Map.entry("48-5#3", "48-5#4"),
                    Map.entry("48-5#4", "48-5#1"),
                    Map.entry("48-5#5", "48-5#2"),
                    Map.entry("50-2#1", "50-2#legacy-1"),
                    Map.entry("50-2#2", "50-2#1"),
                    Map.entry("51-3#1", "51-3#2"),
                    Map.entry("51-3#2", "51-3#1"),
                    Map.entry("51-3#3", "51-3#3"),
                    Map.entry("51-3#4", "51-3#4"),
                    Map.entry("52-2#1", "52-2#2"),
                    Map.entry("52-2#2", "52-2#1"),
                    Map.entry("52-5#1", "52-5#2"),
                    Map.entry("52-5#2", "52-5#1"),
                    Map.entry("53-6#1", "53-6#4"),
                    Map.entry("53-6#2", "53-6#2"),
                    Map.entry("53-6#3", "53-6#3"),
                    Map.entry("53-6#4", "53-6#1"),
                    Map.entry("54-2#1", "54-2#2"),
                    Map.entry("54-2#2", "54-2#1"),
                    Map.entry("56-1#1", "56-1#2"),
                    Map.entry("56-1#2", "56-1#1"),
                    Map.entry("60-5#1", "60-5#2"),
                    Map.entry("60-5#2", "60-5#1"),
                    Map.entry("61-6#1", "61-6#2"),
                    Map.entry("61-6#2", "61-6#1"),
                    Map.entry("62-5#1", "62-5#2"),
                    Map.entry("62-5#2", "62-5#1"),
                    Map.entry("62-5#3", "62-5#3"))));

    /**
     * 別のレッスンへ移した確認クイズの、旧キー（{@link #quizKey}）→ 新キー。
     *
     * <p>クイズのキーは「レッスンID#番号」なので、教材でクイズを移したり詰め直したりすると
     * <b>記録した回答が別の問いの答えとして読まれる</b>（正解が誤答に化け、復習の期限もずれる）。
     * 移した回はここへ1段足す。</p>
     *
     * <p><b>段ごとに印（id）を持つ。</b> 適用済みの印は進捗ファイルへ書き、2度目は読み替えない ――
     * 表には `7-5#3` → `7-5#0` のような<b>同じレッスン内の詰め直し</b>が入るので、印が無いと
     * 読み替え後に保存したファイルをもう一度読み替えて、移動先で答えた記録がさらに動く。</p>
     *
     * @param id  進捗ファイルへ残す印
     * @param map 旧キー → 新キー
     */
    private record QuizMove(String id, Map<String, String> map) { }

    /**
     * クイズの置き場所を直した履歴。古い順に並べる。
     *
     * <ul>
     *   <li>2026-08-26a … `7-5`（可変長引数）の5問のうち4問が、オーバーロード（`7-3`）と
     *       引数のコピー（`7-4`）の内容だったので移した。</li>
     *   <li>2026-08-26b … 同じ形が基礎編とファイル入出力の16レッスンにあった
     *       （章の最後のレッスンへクイズを寄せる作りだったため）。<b>クイズはその内容を
     *       教えたレッスンへ置く</b>方針にそろえ、40問を移して残りを詰め直した。</li>
     * </ul>
     */
    private static final List<QuizMove> QUIZ_MOVES = List.of(
            new QuizMove("ch07-varargs-2026-08-26", Map.of(
                    "7-5#0", "7-3#0",       // オーバーロードとして成立しないのはどれか
                    "7-5#1", "7-3#1",       // f('A') はどちらが呼ばれるか（拡張変換）
                    "7-5#2", "7-4#0",       // 配列の中身の書き換えは呼び出し元にも見える
                    "7-5#4", "7-4#1",       // 別の配列を代入しても呼び出し元は変わらない
                    "7-5#3", "7-5#0")),     // 可変長引数の決まり（移動先で先頭へ来た）
            new QuizMove("quiz-placement-2026-08-26", Map.ofEntries(
                    Map.entry("1-3#0", "1-1#0"),
                    Map.entry("1-3#1", "1-2#0"),
                    Map.entry("1-3#2", "1-3#0"),
                    Map.entry("1-3#3", "1-3#1"),
                    Map.entry("2-5#0", "2-2#0"),
                    Map.entry("2-5#1", "2-3#0"),
                    Map.entry("2-5#2", "2-1#0"),
                    Map.entry("2-5#3", "2-4#0"),
                    Map.entry("2-5#4", "2-5#0"),
                    Map.entry("2-5#5", "2-5#1"),
                    Map.entry("3-5#0", "3-1#0"),
                    Map.entry("3-5#1", "3-5#0"),
                    Map.entry("3-5#2", "3-5#1"),
                    Map.entry("3-5#3", "3-1#1"),
                    Map.entry("4-6#0", "4-4#0"),
                    Map.entry("4-6#1", "4-6#0"),
                    Map.entry("4-6#2", "4-5#0"),
                    Map.entry("4-6#3", "4-6#1"),
                    Map.entry("5-6#0", "5-3#0"),
                    Map.entry("5-6#1", "5-6#0"),
                    Map.entry("5-6#2", "5-1#0"),
                    Map.entry("5-6#3", "5-4#0"),
                    Map.entry("6-5#0", "6-1#0"),
                    Map.entry("6-5#1", "6-1#1"),
                    Map.entry("6-5#2", "6-5#0"),
                    Map.entry("6-5#3", "6-5#1"),
                    Map.entry("8-5#0", "8-2#0"),
                    Map.entry("8-5#1", "8-2#1"),
                    Map.entry("8-5#2", "8-3#0"),
                    Map.entry("8-5#3", "8-5#0"),
                    Map.entry("8-5#4", "8-1#0"),
                    Map.entry("10-4#0", "10-2#0"),
                    Map.entry("10-4#1", "10-4#0"),
                    Map.entry("10-4#2", "10-3#0"),
                    Map.entry("10-4#3", "10-4#1"),
                    Map.entry("12-4#0", "12-1#0"),
                    Map.entry("12-4#1", "12-3#0"),
                    Map.entry("12-4#2", "12-4#0"),
                    Map.entry("12-4#3", "12-4#1"),
                    Map.entry("13-5#0", "13-2#0"),
                    Map.entry("13-5#1", "13-3#0"),
                    Map.entry("13-5#2", "13-4#0"),
                    Map.entry("13-5#3", "13-5#0"),
                    Map.entry("13-5#4", "13-2#1"),
                    Map.entry("15-3#2", "15-2#0"),
                    Map.entry("15-3#3", "15-1#2"),
                    Map.entry("15-3#4", "15-3#2"),
                    Map.entry("16-5#0", "16-3#0"),
                    Map.entry("16-5#1", "16-2#0"),
                    Map.entry("16-5#2", "16-4#2"),
                    Map.entry("16-5#3", "16-5#0"),
                    Map.entry("16-5#4", "16-1#0"),
                    Map.entry("16-5#5", "16-3#1"),
                    Map.entry("17-4#0", "17-1#0"),
                    Map.entry("17-4#1", "17-1#1"),
                    Map.entry("17-4#2", "17-3#0"),
                    Map.entry("17-4#3", "17-4#0"),
                    Map.entry("17-4#4", "17-4#1"),
                    Map.entry("18-5#0", "18-1#0"),
                    Map.entry("18-5#1", "18-2#0"),
                    Map.entry("18-5#2", "18-4#0"),
                    Map.entry("18-5#3", "18-5#0"),
                    Map.entry("19-4#0", "19-1#1"),
                    Map.entry("19-4#1", "19-2#0"),
                    Map.entry("19-4#2", "19-3#1"),
                    Map.entry("19-4#3", "19-4#0"),
                    Map.entry("57-3#1", "57-1#0"),
                    Map.entry("57-3#2", "57-2#0"),
                    Map.entry("57-3#3", "57-3#1"))));

    /**
     * 選択肢を並べ替えたクイズの、<b>入れ替えた相手の位置</b>（クイズキー → `t`）。
     *
     * <p>{@code quizChoices} が持っているのは学習者が選んだ<b>番号</b>なので、教材側で
     * 選択肢を並べ替えると、同じ番号が別の文を指すようになる（正解した記録が誤答に化け、
     * 復習の苦手度もずれる）。並べ替えた回はここへ1段足し、記録した番号を新しい位置へ
     * 読み替える ―― 記録を捨てるより安全で、★・正解数・払ったチップ・復習の期限が
     * どれも動かない。</p>
     *
     * <p>入っているのは<b>2つの位置の入れ替えだけ</b>（正解を置きたい場所へ動かすので、
     * 3つ以上を回す必要がない）。読み替えは「片方なら相手、相手なら片方、ほかはそのまま」で
     * 済む（{@link #migrateQuizChoice}）。</p>
     *
     * @param id  進捗ファイルへ残す印（{@link #appliedQuizSwaps}）
     * @param map クイズキー → 入れ替えた2つの位置
     */
    private record QuizSwap(String id, Map<String, List<Integer>> map) { }

    /**
     * 選択肢を並べ替えた履歴。古い順に並べる。
     *
     * <ul>
     *   <li>2026-08-26 … `ch60`（Spring Boot）・`ch61`（Open Liberty）・`ch62`（Quarkus）の
     *       確認クイズ36問が、<b>すべて正解が先頭</b>だった（先頭を選び続けるだけで36問正解に
     *       なる）。章ごとに正解を4か所へ3問ずつ散らした。全体の分布は健全に見えていたので、
     *       <b>章単位で数えないと出ない</b>偏りだった。同じ検査で見つかった `ch08`（6問中5問が
     *       4番目）と `ch63`（9問中7問が2番目）も同時に散らした（→ `check_quiz_fairness.py`）。</li>
     * </ul>
     */
    private static final List<QuizSwap> QUIZ_SWAPS = List.of(
            new QuizSwap("quiz-positions-2026-08-26", Map.ofEntries(
                    Map.entry("60-1#1", List.of(0, 2)),
                    Map.entry("60-2#0", List.of(0, 1)),
                    Map.entry("60-2#1", List.of(0, 3)),
                    Map.entry("60-3#0", List.of(0, 2)),
                    Map.entry("60-4#0", List.of(0, 3)),
                    Map.entry("60-4#1", List.of(0, 1)),
                    Map.entry("60-5#0", List.of(0, 3)),
                    Map.entry("60-5#1", List.of(0, 1)),
                    Map.entry("60-6#1", List.of(0, 2)),
                    Map.entry("61-1#0", List.of(0, 1)),
                    Map.entry("61-1#1", List.of(0, 3)),
                    Map.entry("61-2#0", List.of(0, 2)),
                    Map.entry("61-3#0", List.of(0, 3)),
                    Map.entry("61-3#1", List.of(0, 1)),
                    Map.entry("61-4#1", List.of(0, 2)),
                    Map.entry("61-5#1", List.of(0, 2)),
                    Map.entry("61-6#0", List.of(0, 1)),
                    Map.entry("61-6#1", List.of(0, 3)),
                    Map.entry("62-1#0", List.of(0, 2)),
                    Map.entry("62-2#0", List.of(0, 3)),
                    Map.entry("62-2#1", List.of(0, 1)),
                    Map.entry("62-3#1", List.of(0, 2)),
                    Map.entry("62-4#0", List.of(0, 1)),
                    Map.entry("62-4#1", List.of(0, 3)),
                    Map.entry("62-5#0", List.of(0, 1)),
                    Map.entry("62-5#1", List.of(0, 3)),
                    Map.entry("62-6#0", List.of(0, 2)),
                    Map.entry("8-1#0", List.of(3, 0)),
                    Map.entry("8-3#0", List.of(3, 1)),
                    Map.entry("8-5#0", List.of(3, 2)),
                    Map.entry("8-5#1", List.of(3, 0)),
                    Map.entry("63-1#1", List.of(2, 3)),
                    Map.entry("63-3#0", List.of(1, 0)),
                    Map.entry("63-3#1", List.of(1, 2)),
                    Map.entry("63-3#2", List.of(2, 1)),
                    Map.entry("63-4#0", List.of(1, 3)),
                    Map.entry("63-4#1", List.of(1, 0)),
                    Map.entry("63-5#0", List.of(1, 2)))));

    /** 適用済みの問題読み替えの印。ファイルへそのまま書き戻す。 */
    private final Set<String> appliedTaskMoves = new LinkedHashSet<>();

    /** 適用済みの読み替えの印。ファイルへそのまま書き戻す。 */
    private final Set<String> appliedQuizMoves = new LinkedHashSet<>();

    /** 適用済みの並べ替えの印（→ {@link QuizSwap}）。ファイルへそのまま書き戻す。 */
    private final Set<String> appliedQuizSwaps = new LinkedHashSet<>();

    /** すべての段を適用済みにする。読み替えるものが無いときと、読み終えたあとに呼ぶ。 */
    private void markMovesApplied() {
        for (TaskMove move : TASK_MOVES) {
            appliedTaskMoves.add(move.id());
        }
        for (QuizMove move : QUIZ_MOVES) {
            appliedQuizMoves.add(move.id());
        }
        for (QuizSwap swap : QUIZ_SWAPS) {
            appliedQuizSwaps.add(swap.id());
        }
    }

    /**
     * 記録してある「選んだ番号」を、選択肢を並べ替えたあとの位置へ読み替える。
     *
     * <p>キーの読み替え（{@link #migrateQuizKey}）を済ませたあとの新しいキーで引く。
     * 適用済みの段は飛ばす（二度読み替えると元の位置へ戻ってしまう）。</p>
     */
    private int migrateQuizChoice(String key, int choice) {
        int moved = choice;
        for (QuizSwap swap : QUIZ_SWAPS) {
            if (appliedQuizSwaps.contains(swap.id())) {
                continue;
            }
            List<Integer> pair = swap.map().get(key);
            if (pair == null) {
                continue;
            }
            if (moved == pair.get(0)) {
                moved = pair.get(1);
            } else if (moved == pair.get(1)) {
                moved = pair.get(0);
            }
        }
        return moved;
    }

    /** 移したクイズのキーを読み替える。適用済みの段は飛ばし、対象外はそのまま返す。 */
    private String migrateQuizKey(String key) {
        String moved = key;
        for (QuizMove move : QUIZ_MOVES) {
            if (!appliedQuizMoves.contains(move.id())) {
                moved = move.map().getOrDefault(moved, moved);
            }
        }
        return moved;
    }

    /** 移した問題のキーを読み替える。適用済みの段は飛ばし、対象外はそのまま返す。 */
    private String migrateTaskKey(String key) {
        String moved = migrateKey(key);
        for (TaskMove move : TASK_MOVES) {
            if (!appliedTaskMoves.contains(move.id())) {
                moved = move.map().getOrDefault(moved, moved);
            }
        }
        return moved;
    }

    // ------------------------------------------------------------ 永続化本体

    /**
     * 進捗ファイルを読み込む。失敗の扱いを<b>2通りに分ける</b>のが要点。
     *
     * <ul>
     *   <li><b>JSONとして読めない</b>（切り詰められた・書きかけ）… 救えるものが無いので、
     *       退避して作り直す。ここで止めてしまうと二度と起動できなくなる。</li>
     *   <li><b>JSONは読めたのに取り込みで落ちた</b> … 中身は無事なのだから<b>消してはいけない</b>。
     *       ファイルに手を付けず {@link LoadFailedException} を投げ、起動を諦める。</li>
     * </ul>
     *
     * <p>以前はこの2つをまとめて1つの {@code catch} で受け、どちらでも
     * 「退避して全消去」していた。{@code catch} の範囲は移行処理や達成条件の再計算まで
     * 覆っていたので、<b>JSONは正しく読めているのに、こちら側の不具合1つで
     * 利用者の★もコードもコインも初期化される</b>状態だった。新しい版を出した直後に
     * いちばん起きやすい事故なので、こちらは消さずに止める側へ寄せている。</p>
     */
    private void load() {
        if (!Files.exists(file)) {
            // まだ何も記録が無いファイルには読み替えるものが無い。印だけ立てておく
            // （立てないと、この実行で書いた新しいキーを次の起動で読み替えてしまう）
            markMovesApplied();
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("進捗ファイルを読めません: " + file, e);
        }
        if (text.isBlank()) {
            markMovesApplied();
            return;
        }

        Map<String, Object> root;
        try {
            root = MiniJson.parseObject(text);
        } catch (RuntimeException e) {
            retireUnreadable(e);
            return;
        }

        try {
            readFrom(root);
        } catch (RuntimeException e) {
            // 中身は無事なのだから、ファイルにも触らず、消しもしない。
            // 半端に読めたぶんが書き戻されないよう、以後の書き出しは全て止める
            writeDisabled = true;
            throw new LoadFailedException(file, nextBackupPath(BROKEN_SUFFIX), e);
        }
    }

    /**
     * ★を読み、通常の問題移動と概念レッスン化の両方を反映する。
     *
     * <p>複数の必須問題があったレッスンは、昔の問題を<b>全部</b>終えていた場合だけ
     * 概念レッスンの1つの★へまとめる。1問目だけの途中記録をレッスン完了へ昇格させない。</p>
     */
    private void readCleared(Map<String, Object> root) {
        Map<String, Cleared> old = new LinkedHashMap<>();
        MiniJson.obj(root, "cleared").forEach((id, value) -> {
            if (!(value instanceof Map)) {
                return;     // 形の違う1件は飛ばす
            }
            Map<String, Object> entry = MiniJson.asObj(value);
            old.put(migrateTaskKey(id), new Cleared(
                    MiniJson.str(entry, "clearedAt", LearningDay.todayText()),
                    MiniJson.intOf(entry, "hintsUsed", 0),
                    MiniJson.intOf(entry, "attempts", 1)));
        });

        Set<String> conceptOldKeys = conceptMigratedTaskKeys();
        old.forEach((key, value) -> {
            if (!conceptOldKeys.contains(key)) {
                cleared.put(key, value);
            }
        });

        CONCEPT_MIGRATED_REQUIRED_TASK_COUNTS.forEach((lessonId, count) -> {
            boolean complete = true;
            String latest = "";
            int hints = 0;
            int mostAttempts = 1;
            for (int number = 1; number <= count; number++) {
                Cleared part = old.get(lessonId + "#" + number);
                if (part == null) {
                    complete = false;
                    break;
                }
                if (part.clearedAt().compareTo(latest) > 0) {
                    latest = part.clearedAt();
                }
                hints = Math.max(hints, part.hintsUsed());
                mostAttempts = Math.max(mostAttempts, part.attempts());
            }
            if (complete) {
                String conceptKey = lessonId + "#" + Lesson.CONCEPT_TASK_ID;
                cleared.putIfAbsent(conceptKey, new Cleared(latest, hints, mostAttempts));
            }
        });
    }

    /** 読み込んだJSONを状態へ移す。ここで落ちるのは<b>このアプリ側の不具合</b>（→ {@link #load()}）。 */
    private void readFrom(Map<String, Object> root) {
        // 問題・クイズの読み替えより先に読む（読み替えるかどうかの判断に使う）
        for (Object o : MiniJson.list(root, "appliedTaskMoves")) {
            if (o instanceof String s) {
                appliedTaskMoves.add(s);
            }
        }
        for (Object o : MiniJson.list(root, "appliedQuizMoves")) {
            if (o instanceof String s) {
                appliedQuizMoves.add(s);
            }
        }
        for (Object o : MiniJson.list(root, "appliedQuizSwaps")) {
            if (o instanceof String s) {
                appliedQuizSwaps.add(s);
            }
        }
        boolean hasCafeState = root.get("cafe") instanceof Map;
        onboardingCompleted = root.get("onboardingCompleted") instanceof Boolean completed
                && completed;

        readCleared(root);
        MiniJson.obj(root, "codes").forEach((id, v) -> {
            if (v instanceof String s) {
                codes.put(migrateTaskKey(id), s);
            }
        });
        MiniJson.obj(root, "hintsRevealed").forEach((id, v) -> {
            if (v instanceof Number n) {
                hintsRevealed.put(migrateTaskKey(id), n.intValue());
            }
        });
        MiniJson.obj(root, "attempts").forEach((id, v) -> {
            if (v instanceof Number n) {
                attempts.put(migrateTaskKey(id), n.intValue());
            }
        });
        MiniJson.obj(root, "bestPassed").forEach((id, v) -> {
            if (v instanceof Number n) {
                bestPassed.put(migrateTaskKey(id), n.intValue());
            }
        });
        MiniJson.obj(root, "quizChoices").forEach((key, v) -> {
            if (v instanceof Number n) {
                String migrated = migrateQuizKey(key);
                int choice = migrateQuizChoice(migrated, n.intValue());
                quizChoices.put(migrated, choice);
                if (choice != n.intValue()) {
                    // 読み替えた形を次の保存で載せる（載るまでは毎回の起動で同じ読み替えが
                    // 走るだけなので、結果は変わらない）。ここでタイマーは起こさない
                    saveEventually();
                }
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
                    reviewWeight.put(migrateTaskKey(id), weight);
                }
            }
        });
        MiniJson.obj(root, "reviewPlans").forEach((id, v) -> {
            if (!(v instanceof Map)) {
                return;     // 同上
            }
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
            // clean が無いファイル（2026-08-19より前）は0から数え直す。
            // 飛び級には一発正解2連続が要るので、いきなり間隔が飛ぶことはない
            int cleanRun = Math.max(0,
                    Math.min(MAX_CLEAN_RUN, MiniJson.intOf(plan, "clean", 0)));
            reviewPlans.put(migrateTaskKey(id),
                    new ReviewPlan(level, lastAt, lastFailAt, cleanRun));
        });
        // クイズの予定も最初から "レッスンID#番号" なので読み替えは要らない
        MiniJson.obj(root, "quizPlans").forEach((id, v) -> {
            if (!(v instanceof Map)) {
                return;     // 同上
            }
            Map<String, Object> plan = MiniJson.asObj(v);
            int level = Math.max(0, Math.min(REVIEW_INTERVAL_DAYS.length - 1,
                    MiniJson.intOf(plan, "level", 0)));
            String lastAt = MiniJson.str(plan, "at", "");
            if (!isDate(lastAt)) {
                // 日付が壊れている行は「今日が期限」に落ちる（→ quizReviewDue）
                lastAt = "";
            }
            quizPlans.put(migrateQuizKey(id), new QuizPlan(level, lastAt));
        });
        for (Object o : MiniJson.list(root, "bookmarks")) {
            if (o instanceof String s) {
                bookmarks.add(migrateTaskKey(s));
            }
        }
        // クイズのしおりは最初から "レッスンID#番号" なので、移したクイズの読み替えだけでよい
        for (Object o : MiniJson.list(root, "quizBookmarks")) {
            if (o instanceof String s) {
                quizBookmarks.add(migrateQuizKey(s));
            }
        }
        // 層の達成日はカフェとは無関係な学習の記録なので、cafe の有無で読み分けない
        // （以前ここが cafe ブロックの中にあり、cafe を持たないセーブでは消えていた）
        for (Map.Entry<String, Object> e
                : MiniJson.obj(root, "layerCompletions").entrySet()) {
            if (e.getValue() instanceof String date && !date.isBlank()) {
                layerCompletions.put(e.getKey(), date);
            }
        }
        if (!root.containsKey("reviewWeight")) {
            seedReviewWeightFromAttempts();
        }

        if (hasCafeState) {
            cafe.loadFrom(root, this::migrateTaskKey);
        } else {
            cafe.migrateFromLearning();
        }
        // フラグ導入前のセーブでも学習履歴があれば既存利用者として扱う。
        onboardingCompleted = onboardingCompleted || hasLearningProgress();
        // すでに条件を満たしている人（連続学習や粘った問題の履歴がある人）へ、
        // 起動した時点でアイテムを解放する。
        cafe.refreshCafeAchievements();
        // ここまで読めたら、すべての段の読み替えは済んだものとして印を立てる
        // （次に保存したファイルには新しいキーが載るので、二度と読み替えない）
        markMovesApplied();
    }

    /**
     * JSONとして読めなかった進捗ファイルを退避して、作り直す。
     *
     * <p>ここへ来るのは中身が切り詰められている（電源断で書きかけが残ったなど）ときで、
     * 読み取れるものが無い。起動できないままにするほうが困るので、控えを取って先へ進む。
     * 控えは上書きしない（{@link #nextBackupPath(String)}）。</p>
     */
    private void retireUnreadable(RuntimeException e) {
        Path backup = nextBackupPath(BROKEN_SUFFIX);
        System.err.println("進捗ファイルが壊れているようです (" + e.getMessage() + ")。"
                + backup.getFileName() + " に退避して作り直します。");
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 退避に失敗しても、以降の書き出しで上書きされる
        }
        // 途中まで読めていた分が残らないよう、全ての状態を初期値へ戻す
        clearAllState();
        // ここから先に書かれるのは現行版のキー。印が無いまま保存すると、次の起動で
        // 新しい問題を古い問題だと誤認して読み替えてしまう。
        markMovesApplied();
    }

    /**
     * 控えの置き場所を決める。**既にある控えを上書きしない**のが役目。
     *
     * <p>{@code progress.json.broken} が空いていればそれを使い、埋まっていれば
     * {@code .broken.2}、{@code .broken.3} …と番号を足していく。
     * 1つの名前を使い回すと、<b>唯一の控えを、リセット直後の空っぽのファイルで
     * 上書きしてしまう</b> ―― 読み込みで落ちる不具合は同じ版なら毎回起きるので、
     * 1回目に取っておいた本物の記録が2回目の起動で消える筋があった。
     * いちばん古い（＝本物である可能性がいちばん高い）控えは必ず残す。
     * リセットの控え（{@link #BEFORE_RESET_SUFFIX}）も同じ理由で同じ数え方をする ――
     * 2度目のリセットで、1度目に取った本物の控えを潰してはいけない。</p>
     *
     * <p>{@link #MAX_BACKUPS} まで埋まったときは、いちばん新しい番号を
     * 上書きする。増え続けてフォルダが埋まるほうを避けるためで、
     * このときも番号なしのものには手を付けない。</p>
     */
    private Path nextBackupPath(String suffix) {
        Path base = file.resolveSibling(file.getFileName() + suffix);
        if (!Files.exists(base)) {
            return base;
        }
        for (int n = 2; n <= MAX_BACKUPS; n++) {
            Path candidate = base.resolveSibling(base.getFileName() + "." + n);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return base.resolveSibling(base.getFileName() + "." + MAX_BACKUPS);
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
            // 当時の1回も採点1回ぶんなので、いまの「失敗1回」と同じ重みで数える
            int misses = Math.min(MAX_REVIEW_WEIGHT,
                    (c.attempts() - 1) * REVIEW_WEIGHT_PER_FAIL);
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
                if (writeDisabled || !dirty) {
                    return;
                }
                dirty = false;
                json = MiniJson.write(toJsonRaw());
            }
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                writeDurably(tmp, json);
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
     * 一時ファイルへ書き、<b>中身がディスクに載るまで待つ</b>。
     *
     * <p>{@code force} を呼ばずに差し替えると、名前の差し替えだけが先にディスクへ載ることがある。
     * その状態で電源が落ちると、次の起動で<b>中身が空か途中で終わっている
     * {@code progress.json}</b> に出会う。そしてこの壊れ方だけは
     * <b>控えを取る先が無い</b> ―― 元の内容はもう差し替えで置き換わっていて、
     * {@code .broken} へ退避できるのは壊れたほうだけである。
     * 取り返しがつかないのはここだけなので、1回ぶんの待ちは払う。</p>
     *
     * <p>待ちは書き出し専用のスレッド（{@code jq-progress-save}）の中で、
     * かつ {@code this} を持たずに起きる。リクエストの処理は止まらない。
     * 実測で保存1回が 0.5ms → 5.5ms になる（37KBの進捗ファイル、APFS）。
     * 保存はいちばん詰まっても1秒に1回なので、待つ人は誰もいない。</p>
     *
     * <p><b>ディレクトリの {@code force} は<u>あえて</u>やらない。</b>
     * 差し替え自体をディスクへ載せると保存1回がさらに 5.5ms → 11.9ms へ倍増するが、
     * 買えるのは「最後の1回ぶんの保存を失わないこと」だけである ――
     * 差し替えが失われたときに残るのは<b>差し替える前の（正しい）ファイル</b>で、
     * 壊れはしない。そして1秒ぶんの取りこぼしは
     * {@link #SAVE_DELAY_MS} がすでに認めている範囲である。
     * 毎回2倍払って、設計上すでに諦めている窓を埋める意味は無い。</p>
     *
     * <p>なお macOS の {@code fsync} は装置のキャッシュまでで、板まで届いたことは
     * 保証しない（{@code F_FULLFSYNC} はJavaから呼べない）。ここで防げるのは
     * OSの巻き添え・パニック・アプリの異常終了までで、
     * 電源そのものが落ちる場合の最後の一線は残る。</p>
     */
    private static void writeDurably(Path tmp, String json) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
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
        // 適用済みの問題・クイズ読み替え。次に読むときは読み替えない
        m.put("appliedTaskMoves", new ArrayList<>(appliedTaskMoves));
        m.put("appliedQuizMoves", new ArrayList<>(appliedQuizMoves));
        m.put("appliedQuizSwaps", new ArrayList<>(appliedQuizSwaps));
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
            pm.put("clean", plan.cleanRun());
            plans.put(key, pm);
        });
        m.put("reviewPlans", plans);
        Map<String, Object> quizPlansJson = new LinkedHashMap<>();
        quizPlans.forEach((key, plan) -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("level", plan.level());
            pm.put("at", plan.lastAt());
            quizPlansJson.put(key, pm);
        });
        m.put("quizPlans", quizPlansJson);
        m.put("bookmarks", new ArrayList<>(bookmarks));
        m.put("quizBookmarks", new ArrayList<>(quizBookmarks));
        m.put("layerCompletions", new LinkedHashMap<>(layerCompletions));

        m.put("cafe", this.cafe.toJson());
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
                || !quizBookmarks.isEmpty()
                || !reviewPlans.isEmpty()
                || !quizPlans.isEmpty();
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
