import jq.progress.ProgressStore;
import jq.json.MiniJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 進捗ファイルが素直に読めないときの振る舞いを確かめる。
 *
 * <p>ここで守りたいのは<b>「読めなかったからといって記録を捨てない」</b>ことである。
 * 失敗は2通りあり、扱いが違う（{@code ProgressStore.load}）。</p>
 *
 * <ul>
 *   <li><b>JSONとして読めない</b>（切り詰められた・書きかけ）… 救えるものが無いので退避して作り直す。
 *       控えは上書きしない（{@code .broken} → {@code .broken.2} → …）。</li>
 *   <li><b>JSONは読めたが1件だけ形が違う</b> … その1件だけ飛ばし、<b>残りは全て保つ</b>。
 *       ファイルも退避しない。以前はここで丸ごと初期化していた。</li>
 * </ul>
 *
 * <p>3つめの「JSONは読めたのに取り込みで落ちた（＝こちら側の不具合）」は、
 * データからは起こせないのでここでは扱わない。そちらは
 * {@code ProgressStore.LoadFailedException} を投げてファイルに触らない作りにしてある
 * （{@code jq.App.openProgress} が案内を出して起動を諦める）。</p>
 */
public final class ProgressLoadCheck {

    /** ★2つ・下書き1つ・コイン入りの、普通の進捗。 */
    private static final String SOUND = "{\"onboardingCompleted\":true,"
            + "\"cleared\":{\"1-1#1\":{\"clearedAt\":\"2026-08-01\",\"hintsUsed\":0,\"attempts\":2},"
            + "\"2-3#1\":{\"clearedAt\":\"2026-08-02\",\"hintsUsed\":1,\"attempts\":1}},"
            + "\"codes\":{\"3-1#1\":\"// 大事な下書き\"},"
            + "\"clearDates\":[\"2026-08-01\",\"2026-08-02\"],"
            + "\"cafe\":{\"cash\":54321,\"lifetimeCash\":99999,\"economyVersion\":2}}";

    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    private static void ok(String label, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("NG " + label);
        }
        System.out.println("OK  " + label);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jq-progress-load-");
        try {
            unreadableIsRetired(dir);
            backupsAreNotOverwritten(dir);
            oneBadEntryDoesNotWipeTheRest(dir);
            emptyFileIsLeftAlone(dir);
            resetKeepsABackup(dir);
            writesLandOnDiskBeforeTheSwap(dir);
            reorderedTasksKeepTheirOwnProgress(dir);
            shuffledChoicesKeepPointingAtTheSameAnswer(dir);
            System.out.println();
            System.out.println("PROGRESS LOAD OK: 読めないファイルの退避（控えを上書きしない）・"
                    + "形の違う1件で全体を捨てないこと・リセット前の控え・"
                    + "差し替える前に中身をディスクへ載せること・"
                    + "並べ替えた課題の進捗を同じ課題へ移すこと・"
                    + "選択肢を並べ替えたクイズの回答の読み替え（1度だけ）を確認しました");
        } finally {
            deleteTree(dir);
        }
    }

    /** JSONとして読めないファイルは、退避して作り直す（起動できないままにはしない）。 */
    private static void unreadableIsRetired(Path dir) throws Exception {
        Path file = dir.resolve("truncated.json");
        // 電源断で書きかけが残った形（閉じ括弧が無い）
        write(file, "{\"onboardingCompleted\":true,\"cleared\":{\"1-1#1\":");
        ProgressStore store = new ProgressStore(file);
        ok("読めないファイルは退避される", !Files.exists(file));
        ok("控えが残る", Files.exists(dir.resolve("truncated.json.broken")));
        ok("進捗は初期化されて起動できる", store.clearedIds().isEmpty());

        store.saveCode("37-2#1", "// 復旧後に書いた新しい実機演習");
        store.flushNow();
        ProgressStore again = new ProgressStore(file);
        ok("復旧後に書いた現行キーを次の起動で読み替えない",
                "// 復旧後に書いた新しい実機演習".equals(again.savedCode("37-2#1")));
    }

    /** 2回目の失敗で、1回目に取っておいた控えを潰さない。 */
    private static void backupsAreNotOverwritten(Path dir) throws Exception {
        Path file = dir.resolve("again.json");
        Path first = dir.resolve("again.json.broken");
        Path second = dir.resolve("again.json.broken.2");

        write(file, SOUND.substring(0, SOUND.length() - 3));    // 本物だが切り詰められている
        new ProgressStore(file);
        ok("1回目は .broken へ", Files.exists(first));

        write(file, "{\"壊れている\":");
        new ProgressStore(file);
        ok("2回目は .broken.2 へ", Files.exists(second));
        ok("1回目の控え（本物の記録）が残っている",
                read(first).contains("大事な下書き"));
        ok("2回目の控えは2回目の中身", read(second).contains("壊れている"));
    }

    /**
     * 1件だけ形が違うファイルで、他の記録を捨てない。
     *
     * <p>{@code cleared} の1件が数値になっている（＝手で編集した・古い版が書いた）状態。
     * 以前はここで例外になり、★もコードもコインも全て初期化されて
     * ファイルまで退避されていた。</p>
     */
    private static void oneBadEntryDoesNotWipeTheRest(Path dir) throws Exception {
        Path file = dir.resolve("odd-entry.json");
        write(file, SOUND.replace("\"2-3#1\":{\"clearedAt\":\"2026-08-02\",\"hintsUsed\":1,\"attempts\":1}",
                "\"2-3#1\":5"));
        ProgressStore store = new ProgressStore(file);

        ok("ファイルは退避されない", Files.exists(file));
        ok("控えも作られない", !Files.exists(dir.resolve("odd-entry.json.broken")));
        ok("形の違う1件だけ落ちる", !store.clearedIds().contains("2-3#1"));
        ok("他の★は残る", store.clearedIds().contains("1-1#1"));
        ok("書いたコードも残る", "// 大事な下書き".equals(store.savedCode("3-1#1")));
        ok("コインも残る", cash(store) == 54321);
        ok("初回案内は済みのまま", store.isOnboardingCompleted());

        // reviewPlans / quizPlans も同じ守り方をしている
        Path plans = dir.resolve("odd-plans.json");
        write(plans, SOUND.substring(0, SOUND.length() - 1)
                + ",\"reviewPlans\":{\"1-1#1\":7},\"quizPlans\":{\"1-1#0\":\"x\"}}");
        ProgressStore planStore = new ProgressStore(plans);
        ok("予定の1件が形違いでも捨てない", planStore.clearedIds().contains("1-1#1")
                && Files.exists(plans));
    }

    /** 中身が無いファイルは、退避もせず初期状態で起動する（書き出しで埋まる）。 */
    private static void emptyFileIsLeftAlone(Path dir) throws Exception {
        Path file = dir.resolve("empty.json");
        write(file, "");
        ProgressStore store = new ProgressStore(file);
        ok("空ファイルは退避しない", Files.exists(file)
                && !Files.exists(dir.resolve("empty.json.broken")));
        ok("空ファイルは初期状態", store.clearedIds().isEmpty());
    }

    /**
     * リセットは控えを取ってから消す。2度目のリセットで1度目の控えを潰さない。
     *
     * <p>壊れたときは {@code .broken} が残るのに、利用者が押したときは何も残らなかった。
     * 設定パネルからワンクリック（確認1回）なので、押し間違いが取り返せなかった。</p>
     */
    private static void resetKeepsABackup(Path dir) throws Exception {
        Path file = dir.resolve("reset.json");
        write(file, SOUND);
        ProgressStore store = new ProgressStore(file);
        ok("リセット前に★がある", store.clearedIds().contains("1-1#1"));

        store.resetAll();
        store.flushNow();

        Path first = dir.resolve("reset.json.before-reset");
        ok("リセット前の控えが残る", Files.exists(first));
        ok("控えには消える前の記録が入っている", read(first).contains("大事な下書き"));
        ok("本体は消えている", store.clearedIds().isEmpty());
        ok("本体のコインも消えている", cash(store) == 0);

        // 2度目のリセットで、1度目の控え（本物の記録）を潰さない
        store.resetAll();
        store.flushNow();
        ok("2度目は .before-reset.2 へ", Files.exists(dir.resolve("reset.json.before-reset.2")));
        ok("1度目の控えは元のまま", read(first).contains("大事な下書き"));
    }

    /**
     * 差し替える前に中身をディスクへ載せる（{@code force}）。
     *
     * <p>ここで確かめられるのは「書けて、読み直せて、途中で終わっていない」ことまでである。
     * 電源を落とす検査はできないので、<b>差し替えのあとに残るのが完全なファイルであること</b>を
     * 押さえておく（{@code .tmp} が残らないことも含めて）。</p>
     */
    private static void writesLandOnDiskBeforeTheSwap(Path dir) throws Exception {
        Path file = dir.resolve("durable.json");
        ProgressStore store = new ProgressStore(file);
        store.saveCode("9-9#1", "// ディスクまで届いているか");
        store.flushNow();

        ok("本体が書かれている", Files.exists(file));
        ok("一時ファイルが残らない", !Files.exists(dir.resolve("durable.json.tmp")));
        ok("書いた内容を読み直せる", read(file).contains("ディスクまで届いているか"));
        ok("途中で終わっていない", read(file).strip().endsWith("}"));
        ok("読み直しても同じ", "// ディスクまで届いているか"
                .equals(new ProgressStore(file).savedCode("9-9#1")));
    }

    /**
     * 必須の実機演習を先頭へ移しても、旧1問目の記録が新しい実機演習へ化けない。
     * ★だけでなく下書き・ヒント・提出回数・復習予定・カフェ内の問題キーもまとめて見る。
     */
    private static void reorderedTasksKeepTheirOwnProgress(Path dir) throws Exception {
        Path file = dir.resolve("task-move.json");
        write(file, """
                {
                  "onboardingCompleted": true,
                  "cleared": {
                    "37-2#1": {"clearedAt":"2026-08-01","hintsUsed":1,"attempts":3},
                    "37-2#2": {"clearedAt":"2026-08-02","hintsUsed":0,"attempts":1}
                  },
                  "codes": {"37-2#1":"// parser", "37-2#2":"// deadlock lab"},
                  "hintsRevealed": {"37-2#1":1, "37-2#2":2},
                  "attempts": {"37-2#1":3, "37-2#2":4},
                  "bestPassed": {"37-2#1":5, "37-2#2":6},
                  "reviewWeightScale": 4,
                  "reviewWeight": {"37-2#1":7, "37-2#2":8},
                  "reviewPlans": {
                    "37-2#1":{"level":1,"at":"2026-08-03","failAt":"","clean":0},
                    "37-2#2":{"level":2,"at":"2026-08-04","failAt":"","clean":1}
                  },
                  "bookmarks": ["37-2#1"],
                  "cafe": {
                    "economyVersion":2,
                    "masteryTaskRun":["37-2#1"],
                    "masteryTasks":["37-2#1"],
                    "reviewPaidTasks":["37-2#1"],
                    "masteryDayTasks":["37-2#1"]
                  }
                }
                """);

        ProgressStore store = new ProgressStore(file);
        ok("旧実機演習の下書きは新1問目へ移る",
                "// deadlock lab".equals(store.savedCode("37-2#1")));
        ok("旧Java集計の下書きは任意の新2問目へ移る",
                "// parser".equals(store.savedCode("37-2#2")));
        ok("ヒント記録も課題と一緒に入れ替わる", store.hintsRevealed("37-2#1") == 2);
        ok("ケース記録も課題と一緒に入れ替わる", store.bestPassed("37-2#1") == 6);
        ok("苦手度も課題と一緒に入れ替わる", store.reviewWeight("37-2#1") == 8);
        ok("しおりも元の課題を指す", store.isBookmarked("37-2#2"));

        // 保存を1回起こし、非公開の記録と適用済み印も書き出された形で確認する。
        store.saveCode("9-9#1", "// save migration");
        store.flushNow();
        Map<String, Object> saved = MiniJson.parseObject(read(file));
        ok("提出回数も課題と一緒に入れ替わる",
                MiniJson.intOf(MiniJson.obj(saved, "attempts"), "37-2#1", 0) == 4);
        ok("復習予定も課題と一緒に入れ替わる",
                MiniJson.obj(saved, "reviewPlans").containsKey("37-2#1"));
        Map<String, Object> cafe = MiniJson.obj(saved, "cafe");
        ok("カフェ内の復習記録も課題と一緒に移る",
                MiniJson.list(cafe, "masteryTasks").contains("37-2#2")
                        && MiniJson.list(cafe, "reviewPaidTasks").contains("37-2#2"));
        ok("課題移動の適用済み印が残る",
                MiniJson.list(saved, "appliedTaskMoves").contains("practice-first-2026-09-04"));

        ProgressStore again = new ProgressStore(file);
        ok("2度目は課題を入れ替え直さない",
                "// deadlock lab".equals(again.savedCode("37-2#1"))
                        && "// parser".equals(again.savedCode("37-2#2")));
    }

    /**
     * 選択肢を並べ替えたクイズで、記録した回答が<b>同じ選択肢を指したまま</b>になる。
     *
     * <p>`quizChoices` が持つのは選んだ<b>番号</b>なので、教材で選択肢を並べ替えると
     * そのままでは別の文を指す（正解が誤答に化ける）。{@code QUIZ_SWAPS} で番号を読み替え、
     * 印を進捗ファイルへ残して<b>二度は読み替えない</b>ことを確かめる。
     * ここで使う `60-2#0` は 0番と1番を入れ替えた回で、正解は1番へ移っている。</p>
     */
    private static void shuffledChoicesKeepPointingAtTheSameAnswer(Path dir) throws Exception {
        Path file = dir.resolve("quiz-swap.json");
        // 並べ替える前に「0番（当時の正解）」を選んで正解していた記録
        write(file, "{\"onboardingCompleted\":true,\"quizChoices\":{\"60-2#0\":0,\"60-1#0\":0}}");

        ProgressStore store = new ProgressStore(file);
        ok("並べ替えた回の回答は新しい位置へ動く", store.quizChoice("60-2", 0) == 1);
        ok("並べ替えていない回はそのまま", store.quizChoice("60-1", 0) == 0);

        store.flushNow();
        ok("適用済みの印が残る", read(file).contains("quiz-positions-2026-08-26"));

        ProgressStore again = new ProgressStore(file);
        ok("2度目は読み替えない（元へ戻らない）", again.quizChoice("60-2", 0) == 1);

        // 先頭以外の入れ替えも同じように動く（`8-5#0` は4番目と3番目の入れ替え）。
        // キーの読み替え（QUIZ_MOVES）は済んだ印を入れて、番号の読み替えだけを見る
        Path other = dir.resolve("quiz-swap-mid.json");
        write(other, "{\"onboardingCompleted\":true,"
                + "\"appliedQuizMoves\":[\"ch07-varargs-2026-08-26\",\"quiz-placement-2026-08-26\"],"
                + "\"quizChoices\":{\"8-5#0\":3,\"8-5#1\":1}}");
        ProgressStore mid = new ProgressStore(other);
        ok("入れ替えた側は相手の位置へ", mid.quizChoice("8-5", 0) == 2);
        ok("入れ替えに関わらない番号はそのまま", mid.quizChoice("8-5", 1) == 1);

        // 印が1つも無い古いファイルでは、キーの読み替え → 番号の読み替えの順で通る
        // （旧 `8-5#3` はいまの `8-5#0` で、そこは4番目と3番目を入れ替えた）
        Path both = dir.resolve("quiz-swap-legacy.json");
        write(both, "{\"onboardingCompleted\":true,\"quizChoices\":{\"8-5#3\":3}}");
        ProgressStore legacy = new ProgressStore(both);
        ok("キーと番号の両方が読み替わる", legacy.quizChoice("8-5", 0) == 2);

        // 記録が無いファイルにも印だけ立てる（この実行で書いた番号を次に読み替えないため）
        Path fresh = dir.resolve("quiz-swap-fresh.json");
        ProgressStore blank = new ProgressStore(fresh);
        blank.saveCode("60-2#1", "// 何か書いた");
        blank.flushNow();
        ok("新しいファイルにも印が入る", read(fresh).contains("quiz-positions-2026-08-26"));
    }

    private static long cash(ProgressStore store) {
        Object cafe = ((Map<?, ?>) store.toClientJson(ZERO)).get("cafe");
        Object value = ((Map<?, ?>) cafe).get("cash");
        return ((Number) value).longValue();
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 一時ディレクトリなので消し残っても構わない
                }
            });
        }
    }
}
