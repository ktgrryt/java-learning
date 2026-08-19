package jq.progress;

import jq.content.Lesson;
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
 * 保存はファイル全体の書き直しになるため、変更のたびには書かない。
 * {@link #saveSoon()}（★や購入など）と {@link #saveEventually()}（自動売上のtick）で
 * 溜めて、{@link #flushNow()} がまとめて1回書く。終了時は {@code jq.App} の
 * シャットダウンフックが最後に {@link #flushNow()} を呼ぶ。
 */
public final class ProgressStore {

    /**
     * 苦手度の目盛り。1点ぶんを何単位で数えるか。
     *
     * <p>提出＝採点なので、コードを書いている途中の失敗まで全部数える。失敗1回で1点上がると、
     * 試行錯誤しただけで最大まで振り切れてしまう。内部を4倍の細かさで持ち、
     * <b>失敗1回は1単位（=0.25点）</b>にしてある。4回失敗して、ようやく従来の1回ぶん。</p>
     *
     * <p>2026-08-19に画面へ「試しに実行」（採点なし・{@code /api/run}）が戻ったが、この目盛りは
     * 緩いままにしてある。試しに実行はここを通らないので、緩さが害になることはない。</p>
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
     * <p>期限は「最後に復習した日 + この間隔」で決まるので、ここを変えれば過去の記録にも
     * 新しい間隔がそのまま効く（期限日は保存しない）。</p>
     *
     * <p>上がり方は一定ではない。<b>できている問題は早く抜ける</b>ようにしてあり、
     * 詰まった問題にだけ回数を使う（→ {@link #updateReviewPlan}・
     * {@link #initialReviewLevel}）。最後まで進むと4か月ごとの確認になる。</p>
     */
    private static final int[] REVIEW_INTERVAL_DAYS = {1, 3, 7, 14, 30, 60, 120};

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
     * <p>こちらは復習の出題には一切関わらない（クイズは解き直す提出物を持たないので、
     * 期限も苦手度も持たない）。押すとそのクイズまで戻れる、しおりだけの役目。</p>
     */
    private final Set<String> quizBookmarks = new LinkedHashSet<>();
    /**
     * 問題キー -> 復習の予定。忘却曲線でいつ確認するかを決める。
     *
     * 載っていない問題は「初クリアの翌日が期限」として扱う（{@link #reviewDue(String)}）。
     */
    private final Map<String, ReviewPlan> reviewPlans = new LinkedHashMap<>();

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
        layerCompletions.put(key, LocalDate.now().toString());
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
        recordMasterySubmission(taskKey, passed, false);
    }

    /**
     * @param fromReview 復習モードからの提出なら true。<b>間隔の飛び級はこれだけで数える</b> ―
     *                   通常のレッスン画面ではクリアした自分の解答が最初から入っているので、
     *                   そのまま提出して通っても「思い出せた」ことにならない。復習は
     *                   ひな形から解き直すので、一発で通ったのなら覚えている
     */
    public synchronized void recordMasterySubmission(String taskKey, boolean passed,
                                                     boolean fromReview) {
        // 下げるのはクリア済みの問題に正解したときだけ。まだ通っていない問題で
        // 1ケースだけ通った提出などを「復習で正解」と数えないため。
        // 失敗は1単位（=0.25点）だけ上げる。書いている途中の失敗も全部ここを通るので、
        // 1回で1点上げると試行錯誤しただけで振り切れてしまう。
        // 正解したときは1点（=4単位）まとめて下げる。
        boolean changed = passed
                ? cleared.containsKey(taskKey) && addReviewWeight(taskKey, -REVIEW_WEIGHT_SCALE)
                : addReviewWeight(taskKey, 1);
        changed |= updateReviewPlan(taskKey, passed, fromReview);
        // カフェは「復習で通したか」だけを見る。★も報酬もここでは動かさない
        changed |= cafe.noteReviewSubmission(taskKey, passed, cleared.containsKey(taskKey));
        if (changed) {
            saveSoon();
        }
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
        String today = LocalDate.now().toString();
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
        return c == null ? LocalDate.now().toString() : c.clearedAt();
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
                : Math.min(plan.level(), REVIEW_INTERVAL_DAYS.length - 1);
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
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days)),
                plan == null ? 0 : plan.cleanRun());
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
        String today = LocalDate.now().toString();
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
     * チップも払わない（復習の原則）。動くのは「復習で連続正解したクイズ」だけで、
     * 📣ひらめきメガホンの解放条件になる。</p>
     */
    public synchronized void recordQuizReview(String lessonId, int index, boolean correct) {
        if (cafe.noteQuizReviewAnswered(quizKey(lessonId, index), correct)) {
            saveSoon();
        }
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
     * <p>提出課題が「問題文の表を写すだけ」だった4レッスンを、解説と確認クイズだけの
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
    private static final Set<String> CONCEPT_MIGRATED_TASK_KEYS =
            Set.of("50-4#1", "53-3#1", "53-4#1", "53-5#1");

    /** 読み替える旧キーの一覧。検査が自前の写しを持たないように公開する。 */
    public static Set<String> conceptMigratedTaskKeys() {
        return CONCEPT_MIGRATED_TASK_KEYS;
    }

    /** 昔の問題キーを、概念レッスンの★のキーへ読み替える。対象外はそのまま返す。 */
    private static String migrateClearedKey(String key) {
        String migrated = migrateKey(key);
        if (!CONCEPT_MIGRATED_TASK_KEYS.contains(migrated)) {
            return migrated;
        }
        return migrated.substring(0, migrated.indexOf('#') + 1) + Lesson.CONCEPT_TASK_ID;
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
                cleared.put(migrateClearedKey(id), new Cleared(
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
                // clean が無いファイル（2026-08-19より前）は0から数え直す。
                // 飛び級には一発正解2連続が要るので、いきなり間隔が飛ぶことはない
                int cleanRun = Math.max(0,
                        Math.min(MAX_CLEAN_RUN, MiniJson.intOf(plan, "clean", 0)));
                reviewPlans.put(migrateKey(id),
                        new ReviewPlan(level, lastAt, lastFailAt, cleanRun));
            });
            for (Object o : MiniJson.list(root, "bookmarks")) {
                if (o instanceof String s) {
                    bookmarks.add(migrateKey(s));
                }
            }
            // クイズのしおりは最初から "レッスンID#番号" なので読み替えは要らない
            for (Object o : MiniJson.list(root, "quizBookmarks")) {
                if (o instanceof String s) {
                    quizBookmarks.add(s);
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
                cafe.loadFrom(root);
            } else {
                cafe.migrateFromLearning();
            }
            // フラグ導入前のセーブでも学習履歴があれば既存利用者として扱う。
            onboardingCompleted = onboardingCompleted || hasLearningProgress();
            // すでに条件を満たしている人（連続学習や粘った問題の履歴がある人）へ、
            // 起動した時点でアイテムを解放する。
            cafe.refreshCafeAchievements();
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
            pm.put("clean", plan.cleanRun());
            plans.put(key, pm);
        });
        m.put("reviewPlans", plans);
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
                || !reviewPlans.isEmpty();
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
