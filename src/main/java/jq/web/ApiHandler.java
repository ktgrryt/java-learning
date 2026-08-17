package jq.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.CurriculumPart;
import jq.content.Lesson;
import jq.content.Quiz;
import jq.content.SourceFile;
import jq.content.Task;
import jq.content.TestCase;
import jq.format.JavaSnippetFormatter;
import jq.judge.CaseResult;
import jq.judge.ArtifactValidator;
import jq.judge.Judge;
import jq.judge.SourceChecker;
import jq.json.MiniJson;
import jq.progress.ProgressStore;
import jq.runner.Diagnostic;
import jq.runner.JavaRunner;
import jq.runner.PreflightRunner;
import jq.runner.ProjectRunner;
import jq.runner.RuntimeLabRunner;
import jq.runner.RunResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * /api/* のリクエストを処理する。
 *
 * <ul>
 *   <li>{@code GET  /api/state}    … 全カリキュラム + 進捗</li>
 *   <li>{@code POST /api/run}      … コードを1回実行するだけ（採点しない）</li>
 *   <li>{@code POST /api/submit}   … 全テストケースで採点し、通れば★を付ける</li>
 *   <li>{@code POST /api/save}     … 書きかけのコードを保存</li>
 *   <li>{@code POST /api/hint}     … ヒントを1つ開示</li>
 *   <li>{@code POST /api/solution} … 模範解答（全ヒント開示後、またはクリア後）</li>
 *   <li>{@code POST /api/preflight} … 外部ツールと開発用ポートの事前確認</li>
 *   <li>{@code POST /api/bookmark} … 問題のブックマークを付け外し（復習モードで絞り込む）</li>
 *   <li>{@code POST /api/onboarding/complete} … 初回案内の完了を保存</li>
 *   <li>{@code POST /api/cafe/purchase} … カフェ設備を購入</li>
 *   <li>{@code POST /api/cafe/automation/purchase} … 自動営業設備を購入</li>
 *   <li>{@code POST /api/cafe/passive/*} … 画面表示中の自動売上を開始・精算・停止</li>
 *   <li>{@code POST /api/cafe/item/purchase} … スペシャルアイテムを購入</li>
 *   <li>{@code POST /api/cafe/items/seen} … 解放済みアイテムの通知を既読にする</li>
 *   <li>{@code POST /api/cafe/expand} … カフェの店舗網を拡大</li>
 *   <li>{@code POST /api/cafe/investment/purchase} … 終盤の任意改装へ投資</li>
 *   <li>{@code POST /api/reset}    … 進捗を全消去</li>
 * </ul>
 *
 * <p>振り分けはこのクラスに1箇所だけ置くが、{@code /api/cafe/*} の中身は {@link CafeApi} が持つ。
 * 学習の採点とカフェの経営は目的が別で、混ぜるとこのクラスが両方の入口になってしまう。</p>
 */
public final class ApiHandler implements HttpHandler {

    /**
     * 受け取るリクエスト本文の上限。
     *
     * <p>いちばん大きい正当な本文は project 問題の提出で、教材が許す文字数は
     * {@link ProjectRunner#TOTAL_LIMIT_CHARS}（30万字）である。日本語のコメント込みだと
     * UTF-8で1字3バイトになり、JSONのエスケープも乗るので、<b>文字数の上限を
     * バイト数へ換算してから決める</b>。ここを文字数と同じ数にしていたため、
     * 以前は「教材としては許しているのに、手前で大きすぎるとして断る」大きさの帯があった。</p>
     */
    private static final int MAX_BODY_BYTES = 4 * ProjectRunner.TOTAL_LIMIT_CHARS;

    /**
     * 同時に走らせるコード実行の数。
     *
     * 実行は1件でも最大5秒×ケース数のあいだリクエスト処理スレッドを占有する。
     * 数を絞らないと、タブを何枚か開いて提出しただけで全スレッドが実行で埋まり、
     * 画面ファイルの配信まで待たされてアプリが固まったように見える。
     * 普通の使い方（1タブで1問ずつ）なら1〜2件しか同時に走らないので、
     * 4件あれば足りる。
     */
    static final int MAX_CONCURRENT_RUNS = 4;

    /** 実行スロットが空くのを待つ上限。これを過ぎたら混雑として断る。 */
    private static final long RUN_SLOT_WAIT_MS = 2_000;

    private final ContentLoader loader;
    private final ProgressStore progress;
    private final JavaRunner runner = new JavaRunner();
    private final ProjectRunner projectRunner = new ProjectRunner();
    private final RuntimeLabRunner runtimeLabRunner = new RuntimeLabRunner();
    private final PreflightRunner preflightRunner = new PreflightRunner();
    private final AtomicReference<Curriculum> curriculum = new AtomicReference<>();
    /** 最後に読み込んだ教材の印（{@link ContentLoader#fingerprint()}）。0 は「分からない」。 */
    private final java.util.concurrent.atomic.AtomicLong contentStamp =
            new java.util.concurrent.atomic.AtomicLong();
    /** 先に待った人から順に通す（fair）。混んでいるときに特定の提出だけ待たされ続けないように。 */
    private final Semaphore runSlots = new Semaphore(MAX_CONCURRENT_RUNS, true);
    /** カフェの経営（{@code /api/cafe/*}）。学習の採点とは別の口なので分けてある。 */
    private final CafeApi cafeApi;

    public ApiHandler(ContentLoader loader, ProgressStore progress) {
        this.loader = loader;
        this.progress = progress;
        this.cafeApi = new CafeApi(progress, curriculum::get);
        this.curriculum.set(loader.load());
        this.contentStamp.set(loader.fingerprint());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // 他サイトのページから叩かれると、そのまま任意コード実行になる。
            // 中身を読む前に、このマシンの画面から来たリクエストかを確かめる
            if (!RequestGuard.isAllowed(exchange)) {
                RequestGuard.logRejection(exchange);
                sendError(exchange, 403, RequestGuard.REJECT_MESSAGE);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equals(method) && path.equals("/api/state")) {
                // コンテンツを編集したらブラウザの再読み込みだけで反映されるよう毎回読み直す
                reloadContent();
                sendJson(exchange, 200, state());
                return;
            }
            if (!"POST".equals(method)) {
                sendError(exchange, 405, "このURLは " + method + " を受け付けません: " + path);
                return;
            }

            Map<String, Object> body = readJsonBody(exchange);
            switch (path) {
                // この2つだけが子プロセスを起こす。他の口を待たせないよう数を絞る
                case "/api/run" -> sendJson(exchange, 200, inRunSlot(() -> doRun(body)));
                case "/api/submit" -> sendJson(exchange, 200, inRunSlot(() -> doSubmit(body)));
                case "/api/preflight" -> sendJson(exchange, 200,
                        inRunSlot(() -> doPreflight(body)));
                case "/api/save" -> sendJson(exchange, 200, doSave(body));
                case "/api/hint" -> sendJson(exchange, 200, doHint(body));
                case "/api/quiz" -> sendJson(exchange, 200, doQuiz(body));
                case "/api/solution" -> sendJson(exchange, 200, doSolution(body));
                case "/api/bookmark" -> sendJson(exchange, 200, doBookmark(body));
                case "/api/onboarding/complete" ->
                        sendJson(exchange, 200, doOnboardingComplete());
                // カフェの経営はまとめて CafeApi が受ける（採点の入口と混ぜない）
                case "/api/cafe/purchase" -> sendJson(exchange, 200, cafeApi.purchaseUpgrade(body));
                case "/api/cafe/automation/purchase" ->
                        sendJson(exchange, 200, cafeApi.purchaseAutomation(body));
                case "/api/cafe/passive/start" ->
                        sendJson(exchange, 200, cafeApi.passiveSales(body, "start"));
                case "/api/cafe/passive/collect" ->
                        sendJson(exchange, 200, cafeApi.passiveSales(body, "collect"));
                case "/api/cafe/passive/stop" ->
                        sendJson(exchange, 200, cafeApi.passiveSales(body, "stop"));
                case "/api/cafe/item/purchase" -> sendJson(exchange, 200, cafeApi.purchaseItem(body));
                case "/api/cafe/items/seen" -> sendJson(exchange, 200, cafeApi.markItemsSeen());
                case "/api/cafe/expand" -> sendJson(exchange, 200, cafeApi.expand());
                case "/api/cafe/investment/purchase" ->
                        sendJson(exchange, 200, cafeApi.purchaseInvestment());
                case "/api/reset" -> {
                    progress.resetAll();
                    sendJson(exchange, 200, state());
                }
                default -> sendError(exchange, 404, "そんなURLはありません: " + path);
            }
        } catch (BadRequest e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Busy e) {
            sendError(exchange, 503, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "サーバ内部でエラーが起きました: " + e);
        } finally {
            exchange.close();
        }
    }

    // -------------------------------------------------------------- handlers

    private Object doPreflight(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        Lesson lesson = curriculum.get().lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        if (!lesson.isPreflight()) {
            throw new BadRequest("事前確認レッスンではありません: " + lessonId);
        }
        Map<String, Object> result = new LinkedHashMap<>(
                preflightRunner.run(lesson.preflight()).toJson());
        result.put("lessonId", lessonId);
        return result;
    }

    /** カリキュラム全体と進捗をまとめて返す。画面はこれ1本で描ける。 */
    private Object state() {
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();
        progress.ensureCafeCompletionCatchUp(
                currentCurriculumClearedTaskCount(c, cleared), c.totalTaskCount());

        List<Object> chapters = new ArrayList<>();
        for (Chapter ch : c.chapters()) {
            Map<String, Object> chJson = ch.toPublicJson();
            chJson.put("cleared", c.isChapterCleared(ch, cleared));
            chJson.put("clearedCount", c.clearedCount(ch, cleared));
            chJson.put("taskCount", c.taskCount(ch));
            chJson.put("layers", chapterLayers(c, ch, cleared));
            chJson.put("rubric", chapterRubric(c, ch, cleared));

            @SuppressWarnings("unchecked")
            List<Object> lessons = (List<Object>) chJson.get("lessons");
            for (Object lo : lessons) {
                @SuppressWarnings("unchecked")
                Map<String, Object> lJson = (Map<String, Object>) lo;
                String id = (String) lJson.get("id");
                Lesson lesson = c.lesson(id).orElseThrow();
                lJson.put("cleared", c.isLessonCleared(lesson, cleared));
                lJson.put("clearedCount", c.clearedCount(lesson, cleared));
                lJson.put("quizResults", quizResults(lesson));
                lJson.put("rubric", lessonRubric(c, lesson, cleared));

                @SuppressWarnings("unchecked")
                List<Object> tasks = (List<Object>) lJson.get("tasks");
                for (int i = 0; i < tasks.size(); i++) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tJson = (Map<String, Object>) tasks.get(i);
                    Task task = lesson.tasks().get(i);
                    String key = Lesson.taskKey(id, task.id());
                    tJson.put("cleared", cleared.contains(key));
                    if (task.isMultiFile()) {
                        tJson.put("savedFiles", savedProjectFiles(task, progress.savedCode(key)));
                    } else {
                        tJson.put("savedCode", progress.savedCode(key));
                    }
                    tJson.put("hintsRevealed", progress.hintsRevealed(key));
                    tJson.put("revealedHints", revealedHints(id, task));
                    tJson.put("passedCount", progress.bestPassed(key));
                    tJson.put("solutionUnlocked", solutionUnlocked(id, task, cleared));
                    tJson.put("bookmarked", progress.isBookmarked(key));
                    putReviewState(tJson, key, cleared.contains(key));
                }
            }
            chapters.add(chJson);
        }

        int quizTotal = 0;
        int quizCorrect = 0;
        for (Chapter ch : c.chapters()) {
            for (Lesson lesson : ch.lessons()) {
                quizTotal += lesson.quizzes().size();
                quizCorrect += correctQuizCount(lesson);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> parts = new ArrayList<>();
        for (CurriculumPart part : c.parts()) {
            parts.add(part.toPublicJson());
        }
        m.put("parts", parts);
        m.put("chapters", chapters);
        m.put("progress", progress.toClientJson(CafeApi.learningProgress(c, cleared)));
        m.put("totalLessons", c.totalLessonCount());
        m.put("totalTasks", c.totalTaskCount());
        m.put("quizTotal", quizTotal);
        m.put("quizCorrect", quizCorrect);
        return m;
    }

    /**
     * 提出・クイズ回答のあとに返す差分。
     *
     * 全カリキュラムを返すと毎回3MB以上を送り直すことになる（解説やサンプルは
     * 何も変わっていないのに）。画面が描き直しに必要なのは進捗まわりだけなので、
     * それだけを返して、ブラウザ側は手元の state に上書きする
     * （{@code web/app.js} の {@code applyDelta}）。
     *
     * <p>返すのは<b>提出した問題のレッスンと、その章だけ</b>にする。1回の提出で変わり得るのは
     * その問題・そのレッスン・その章・進捗の集計に閉じていて、他の章の★・苦手度・期限は動かない。
     * 全章ぶんを並べていた頃は、1問の提出やクイズ1問の回答ごとに問題666件とレッスン355件
     * （実測で約160KB）を送っていた。</p>
     *
     * <p>章には{@code layers}（3層の到達状況）も載せる。絞ったので1章ぶんの計算で済み、
     * 「章末の問題を通したのに層の表示が変わらない」（再読み込みするまで古いままだった）
     * が直る。{@code rubric} は画面に出さないので載せない。</p>
     *
     * @param lessonId 影響を受けたレッスン。呼び出し前に実在を確かめてあること
     */
    private Object delta(String lessonId) {
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();
        progress.ensureCafeCompletionCatchUp(
                currentCurriculumClearedTaskCount(c, cleared), c.totalTaskCount());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("progress", progress.toClientJson(CafeApi.learningProgress(c, cleared)));
        m.put("quizCorrect", totalCorrectQuizCount(c));

        Lesson lesson = c.lesson(lessonId).orElse(null);
        Chapter chapter = lesson == null ? null : c.chapterOf(lessonId);
        if (chapter == null) {
            // 採点中に教材が書き換わってレッスンが消えた場合。★や報酬はもう記録済みなので、
            // 引けるところまで（進捗の集計だけ）返す。ここで失敗にすると、通った提出が
            // エラーとして見えてしまう
            return m;
        }

        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("id", chapter.id());
        cm.put("cleared", c.isChapterCleared(chapter, cleared));
        cm.put("clearedCount", c.clearedCount(chapter, cleared));
        cm.put("layers", chapterLayers(c, chapter, cleared));

        Map<String, Object> lm = new LinkedHashMap<>();
        lm.put("id", lesson.id());
        lm.put("cleared", c.isLessonCleared(lesson, cleared));
        lm.put("clearedCount", c.clearedCount(lesson, cleared));

        // 同じレッスンの他の問題も入れる。ヒントや★は問題ごとだが、まとめて送っても数件で済む
        List<Object> tasks = new ArrayList<>();
        for (Task task : lesson.tasks()) {
            String key = Lesson.taskKey(lesson.id(), task.id());
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("lessonId", lesson.id());
            tm.put("taskId", task.id());
            tm.put("cleared", cleared.contains(key));
            tm.put("passedCount", progress.bestPassed(key));
            tm.put("hintsRevealed", progress.hintsRevealed(key));
            tm.put("solutionUnlocked", solutionUnlocked(lesson.id(), task, cleared));
            tm.put("bookmarked", progress.isBookmarked(key));
            putReviewState(tm, key, cleared.contains(key));
            tasks.add(tm);
        }

        m.put("chapters", List.of(cm));
        m.put("lessons", List.of(lm));
        m.put("tasks", tasks);
        m.put("lessonId", lessonId);
        m.put("quizResults", quizResults(lesson));
        return m;
    }

    /** 正解した確認クイズの総数。画面のヘッダに出す合計なので、全レッスンから数える。 */
    private int totalCorrectQuizCount(Curriculum c) {
        int correct = 0;
        for (Chapter ch : c.chapters()) {
            for (Lesson lesson : ch.lessons()) {
                correct += correctQuizCount(lesson);
            }
        }
        return correct;
    }

    /**
     * すでに開示済みのヒント本文。まだ開けていないものは含めない。
     *
     * 開示済みのものは隠す意味がないので最初から state に載せる。こうしないと
     * レッスンを開き直すたびに、件数ぶんの POST /api/hint を直列に投げることになる。
     */
    private List<Object> revealedHints(String lessonId, Task task) {
        int revealed = Math.min(
                progress.hintsRevealed(Lesson.taskKey(lessonId, task.id())),
                task.hints().size());
        List<Object> texts = new ArrayList<>(revealed);
        for (int i = 0; i < revealed; i++) {
            texts.add(task.hints().get(i));
        }
        return texts;
    }

    /**
     * 章の3層（概念／コード／実践）の進み具合と、最初に達成した日を返す。
     *
     * <p>達成した層はその場で記録する。導出だけにすると、章へ問題が増えた瞬間に
     * 過去の達成が未達成へ戻ってしまう（{@code ProgressStore#recordLayerCompletion}）。
     */
    private Map<String, Object> chapterLayers(Curriculum c, Chapter chapter, Set<String> cleared) {
        Map<String, Object> layers = new LinkedHashMap<>();
        for (Curriculum.Layer layer : Curriculum.Layer.values()) {
            Curriculum.LayerProgress p = c.layerProgress(chapter, layer, cleared,
                    (lessonId, index) -> {
                        Integer choice = progress.quizChoice(lessonId, index);
                        return choice != null && c.lesson(lessonId)
                                .map(l -> choice == l.quizzes().get(index).answer())
                                .orElse(false);
                    });
            if (p.complete()) {
                progress.recordLayerCompletion(chapter.id(), layer.id());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", p.total());
            m.put("done", p.done());
            m.put("complete", p.complete());
            m.put("completedAt", progress.layerCompletedAt(chapter.id(), layer.id()));
            layers.put(layer.id(), m);
        }
        return layers;
    }

    /**
     * 章の実務rubric（§8.4）。軸ごとに0〜2点と、実務修了の条件を満たすかを返す。
     *
     * <p>測っていない軸は{@code measured=false}にして、0点と区別する。
     * その章に手段が無いことと、測ったが達成していないことは別である。
     */
    private Map<String, Object> chapterRubric(Curriculum c, Chapter chapter, Set<String> cleared) {
        java.util.function.BiPredicate<String, Integer> correct = (lessonId, index) -> {
            Integer choice = progress.quizChoice(lessonId, index);
            return choice != null && c.lesson(lessonId)
                    .map(l -> choice == l.quizzes().get(index).answer())
                    .orElse(false);
        };
        Map<String, Object> dimensions = new LinkedHashMap<>();
        int earned = 0;
        int available = 0;
        for (String dimension : Task.RUBRIC_DIMENSIONS) {
            Curriculum.RubricScore score = c.rubricScore(chapter, dimension, cleared, correct);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", score.total());
            m.put("done", score.done());
            m.put("points", score.measured() ? score.points() : null);
            m.put("measured", score.measured());
            dimensions.put(dimension, m);
            if (score.measured()) {
                available += 2;
                earned += score.points();
            }
        }
        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("dimensions", dimensions);
        rubric.put("earned", earned);
        rubric.put("available", available);
        rubric.put("meetsThreshold", c.meetsRubricThreshold(chapter, cleared, correct));
        return rubric;
    }

    /**
     * レッスンのrubric。軸ごとの対象数と達成数だけを返す（点数は章でまとめて出す）。
     *
     * <p>「この章のどこで診断を学ぶのか」を、レッスン一覧の並びで読めるようにするため。
     * 対象が無い軸は入れない。空の軸を並べても情報にならない。
     */
    private Map<String, Object> lessonRubric(Curriculum c, Lesson lesson, Set<String> cleared) {
        java.util.function.BiPredicate<String, Integer> correct = (lessonId, index) -> {
            Integer choice = progress.quizChoice(lessonId, index);
            return choice != null && c.lesson(lessonId)
                    .map(l -> choice == l.quizzes().get(index).answer())
                    .orElse(false);
        };
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String dimension : Task.RUBRIC_DIMENSIONS) {
            Curriculum.RubricScore score =
                    c.lessonRubricScore(lesson, dimension, cleared, correct);
            if (!score.measured()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", score.total());
            m.put("done", score.done());
            dimensions.put(dimension, m);
        }
        return dimensions;
    }

    /**
     * レッスンのクイズの回答状況。まだ答えていない問題は null にする。
     *
     * 答えた問題については正解の番号と解説も返す（答え合わせ済みなので隠す意味がない）。
     * 答える前に正解が漏れないよう、null のときは何も入れない。
     */
    private List<Object> quizResults(Lesson lesson) {
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < lesson.quizzes().size(); i++) {
            Integer choice = progress.quizChoice(lesson.id(), i);
            if (choice == null) {
                results.add(null);
                continue;
            }
            Quiz quiz = lesson.quizzes().get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("choice", choice);
            r.put("correct", choice == quiz.answer());
            r.put("answer", quiz.answer());
            r.put("explanation", quiz.explanation());
            results.add(r);
        }
        return results;
    }

    private int correctQuizCount(Lesson lesson) {
        int n = 0;
        for (int i = 0; i < lesson.quizzes().size(); i++) {
            Integer choice = progress.quizChoice(lesson.id(), i);
            if (choice != null && choice == lesson.quizzes().get(i).answer()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 採点なしで1回実行する。「まず動かしてみる」ためのボタン。
     *
     * {@code lessonId} が付いていれば、書きかけのコードとして保存もする。
     * サンプルコードの試し打ちのように「保存はしたくないが同梱ライブラリだけ知りたい」場合は、
     * 代わりに {@code libLessonId} を送る（参照専用。保存しない）。
     */
    private Object doRun(Map<String, Object> body) {
        String code = requireCode(body);
        String stdin = MiniJson.str(body, "stdin", "");
        String lessonId = MiniJson.str(body, "lessonId", "");
        if (!lessonId.isEmpty()) {
            String taskId = taskId(body);
            requireTask(lessonId, taskId);   // 知らないIDで progress.json を汚さない
            progress.saveCode(Lesson.taskKey(lessonId, taskId), code);
        }

        // 同梱ライブラリの引き当て元。lessonId があればそれを、無ければ参照専用の libLessonId を使う
        String libLessonId = lessonId.isEmpty() ? MiniJson.str(body, "libLessonId", "") : lessonId;

        Map<String, Object> result = new LinkedHashMap<>();
        try (JavaRunner.Compiled compiled = runner.compile(code, libSourcesOf(libLessonId))) {
            result.put("compiled", compiled.success());
            result.put("diagnostics", diagnosticsJson(compiled.diagnostics()));
            if (!compiled.success()) {
                return result;
            }
            RunResult run = runner.run(compiled, stdin);
            result.put("run", run.toJson());
            result.put("hint", run.timedOut()
                    ? "5秒以内に終わりませんでした。ループが止まらなくなっていないか確かめましょう。"
                    : jq.runner.ErrorTranslator.forRuntimeError(run.stderr()));
        }
        return result;
    }

    /**
     * 全テストケースで採点する。全部通ったら★を付ける（★は問題ごと）。
     *
     * {@code review} が付いた提出（復習モード）では、書いたコードを保存しない。
     * 復習はひな形から解き直すので、途中の中身を保存すると、すでに通した解答が
     * 上書きされてしまう。採点・苦手度・達成条件の扱いは通常の提出と同じ。
     */
    private Object doSubmit(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        Task task = lesson.task(taskId)
                .orElseThrow(() -> new BadRequest("知らない問題です: " + lessonId + "#" + taskId));
        String key = Lesson.taskKey(lessonId, taskId);
        String code = task.isMultiFile() ? "" : requireCode(body);
        Map<String, String> projectFiles = task.isMultiFile() ? requireProjectFiles(body, task) : Map.of();

        if (body.get("review") != Boolean.TRUE) {
            progress.saveCode(key, task.isMultiFile() ? MiniJson.write(projectFiles) : code);
        }
        int attempts = progress.recordAttempt(key);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lessonId", lessonId);
        result.put("taskId", taskId);
        result.put("attempts", attempts);

        if (task.isProject()) {
            ProjectRunner.Result projectResult = projectRunner.run(task.project(), projectFiles);
            result.putAll(projectResult.toJson());
            progress.recordPassed(key, projectResult.allPass() ? 1 : 0);
            progress.recordMasterySubmission(key, projectResult.allPass());
            if (projectResult.allPass()) {
                addClearRewards(result, c, lesson, task, lessonId, taskId, key);
            }
            result.put("delta", delta(lessonId));
            return result;
        }

        if (task.isRuntimeLab()) {
            RuntimeLabRunner.Result runtimeResult = runtimeLabRunner.run(task.runtimeLab(), projectFiles);
            result.putAll(runtimeResult.toJson());
            if (runtimeResult.available() && runtimeResult.started()) {
                progress.recordPassed(key, (int) runtimeResult.checks().stream()
                        .filter(RuntimeLabRunner.CheckResult::pass).count());
                progress.recordMasterySubmission(key, runtimeResult.allPass());
                if (runtimeResult.allPass()) {
                    addClearRewards(result, c, lesson, task, lessonId, taskId, key);
                }
            }
            result.put("delta", delta(lessonId));
            return result;
        }

        if (task.isArtifact()) {
            ArtifactValidator.Result validation = ArtifactValidator.validate(task.artifact(), code);
            result.put("artifact", true);
            result.put("syntaxValid", validation.syntaxValid());
            result.put("syntaxError", validation.syntaxError());
            result.put("checks", validation.checksJson());
            result.put("passedCount", validation.passedCount());
            result.put("allPass", validation.allPass());
            progress.recordPassed(key, validation.passedCount());
            progress.recordMasterySubmission(key, validation.allPass());
            if (validation.allPass()) {
                addClearRewards(result, c, lesson, task, lessonId, taskId, key);
            }
            result.put("delta", delta(lessonId));
            return result;
        }

        try (JavaRunner.Compiled compiled = runner.compile(code, lesson.libSources())) {
            result.put("compiled", compiled.success());
            result.put("diagnostics", diagnosticsJson(compiled.diagnostics()));
            if (!compiled.success()) {
                result.put("allPass", false);
                result.put("cases", List.of());
                progress.recordMasterySubmission(key, false);
                result.put("delta", delta(lessonId));
                return result;
            }

            List<String> sourceFailures = SourceChecker.failures(task.sourceChecks(), code);
            result.put("sourceFailures", sourceFailures);

            // コンパイルは1回だけ。あとは標準入力を変えて必要な回数だけ走らせる
            List<Object> cases = new ArrayList<>();
            int passed = 0;
            for (TestCase tc : task.cases()) {
                RunResult run = runner.run(compiled, tc.stdin());
                CaseResult cr = Judge.judge(tc, run);
                if (cr.pass()) {
                    passed++;
                }
                cases.add(cr.toJson());
            }
            boolean allPass = sourceFailures.isEmpty() && passed == task.cases().size();
            result.put("cases", cases);
            result.put("passedCount", passed);
            result.put("allPass", allPass);
            progress.recordPassed(key, passed);
            progress.recordMasterySubmission(key, allPass);

            if (allPass) addClearRewards(result, c, lesson, task, lessonId, taskId, key);
        }
        result.put("delta", delta(lessonId));
        return result;
    }

    /** 問題形式に依存しない、初回クリア報酬と次問題の情報を応答へ加える。 */
    private void addClearRewards(Map<String, Object> result, Curriculum c, Lesson lesson, Task task,
                                 String lessonId, String taskId, String key) {
        addClearRewards(result, c, lesson, task.isOptional(), lessonId, taskId, key);
    }

    /**
     * 問題形式に依存しない、初回クリア報酬と次問題の情報を応答へ加える。
     *
     * <p>{@link Task} を受け取らないのは、概念レッスンの★（クイズ全問正解）にも同じ経路を
     * 使うためである。★の付き方が変わっても、報酬・章クリア・次の問題の扱いは1箇所に置く。
     *
     * @return 今回支払った報酬。呼び出し側でクイズのチップと合算するために返す
     */
    private ProgressStore.CafeAward addClearRewards(
            Map<String, Object> result, Curriculum c, Lesson lesson,
            boolean optional, String lessonId, String taskId, String key) {
        Set<String> before = progress.clearedIds();
        Chapter chapter = Objects.requireNonNull(
                c.chapterOf(lessonId), "章が引けません: " + lessonId);
        boolean chapterWasCleared = c.isChapterCleared(chapter, before);
        boolean lessonWasCleared = c.isLessonCleared(lesson, before);

        boolean firstTime = progress.markCleared(key);
        Set<String> after = progress.clearedIds();
        if (optional) {
            result.put("newStar", false);
            result.put("optionalComplete", firstTime);
            result.put("lessonCleared", false);
            result.put("chapterCleared", false);
            result.put("chapterTitle", chapter.title());
            result.put("chapterNumber", chapter.partNumber());
            result.put("cafeAward", CafeApi.awardJson(ProgressStore.CafeAward.NONE));
            result.put("next", null);
            result.put("allChaptersCleared",
                    currentCurriculumClearedTaskCount(c, after) == c.totalTaskCount());
            return ProgressStore.CafeAward.NONE;
        }
        ProgressStore.CafeLearningProgress cafeLearningAfter = CafeApi.learningProgress(c, after);
        boolean chapterCompletedNow = firstTime && !chapterWasCleared
                && c.isChapterCleared(chapter, after);
        ProgressStore.CafeAward cafeAward = firstTime
                ? progress.rewardTask(cafeLearningAfter, key)
                : ProgressStore.CafeAward.NONE;
        if (c.isChapterCleared(chapter, after)) {
            progress.noteChapterAchievements(chapterTaskKeys(chapter));
        }
        boolean chapterCleared = false;
        if (chapterCompletedNow) {
            ProgressStore.CafeAward chapterAward = progress.rewardChapter(
                    chapter.id(), cafeLearningAfter, c.taskCount(chapter));
            chapterCleared = chapterAward.cash() > 0 || chapterAward.cups() > 0;
            cafeAward = cafeAward.plus(chapterAward);
        }

        result.put("newStar", firstTime);
        result.put("lessonCleared", !lessonWasCleared && c.isLessonCleared(lesson, after));
        result.put("chapterCleared", chapterCleared);
        result.put("chapterTitle", chapter.title());
        result.put("chapterNumber", chapter.partNumber());
        result.put("cafeAward", CafeApi.awardJson(cafeAward));
        Curriculum.TaskRef next = c.nextTask(lessonId, taskId);
        result.put("next", next == null ? null : next.toJson());
        result.put("allChaptersCleared",
                currentCurriculumClearedTaskCount(c, after) == c.totalTaskCount());
        return cafeAward;
    }

    private Object doSave(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        // 知らないレッスンIDでも保存できると、progress.json に無関係なキーをいくらでも
        // 積める（消す手立ては画面に無い）。実在する問題の下書きだけを受け付ける
        Task task = requireTask(lessonId, taskId);
        if (task.isMultiFile()) {
            progress.saveCode(Lesson.taskKey(lessonId, taskId),
                    MiniJson.write(requireProjectFiles(body, task)));
        } else {
            progress.saveCode(Lesson.taskKey(lessonId, taskId), requireCode(body));
        }
        return Map.of("ok", true);
    }

    /** 初回案内の完了を、応答を返す前にセーブデータへ書き出す。 */
    private Object doOnboardingComplete() {
        progress.completeOnboarding();
        progress.flushNow();
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();
        return Map.of("delta", Map.of(
                "progress", progress.toClientJson(CafeApi.learningProgress(c, cleared))));
    }

    /**
     * 選択式クイズの答え合わせ。
     *
     * 正解の番号はブラウザへ渡していないので、判定はここでしかできない。
     * 答え直しは許す（最後に選んだものを記録する）。
     */
    private Object doQuiz(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        int index = MiniJson.intOf(body, "index", -1);
        int choice = MiniJson.intOf(body, "choice", -1);

        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        if (index < 0 || index >= lesson.quizzes().size()) {
            throw new BadRequest("そのクイズはありません");
        }
        Quiz quiz = lesson.quizzes().get(index);
        if (choice < 0 || choice >= quiz.choices().size()) {
            throw new BadRequest("その選択肢はありません");
        }

        boolean correct = choice == quiz.answer();
        progress.recordQuiz(lessonId, index, choice, correct);
        ProgressStore.CafeLearningProgress cafeLearning =
                CafeApi.learningProgress(c, progress.clearedIds());
        ProgressStore.CafeAward cafeAward = correct
                ? progress.rewardQuiz(lessonId, index, cafeLearning)
                : ProgressStore.CafeAward.NONE;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lessonId", lessonId);
        m.put("index", index);
        m.put("choice", choice);
        m.put("correct", correct);
        m.put("answer", quiz.answer());
        m.put("explanation", quiz.explanation());
        m.put("cafeAward", CafeApi.awardJson(cafeAward));

        // 概念レッスンは提出課題を持たないので、★の根拠はクイズ全問正解しかない。
        // 全問そろった回に、問題を解いたときと同じ経路で★・章クリア・報酬を出す。
        if (lesson.concept() && correctQuizCount(lesson) == lesson.quizzes().size()) {
            addConceptClearRewards(m, c, lesson, cafeAward);
        }
        m.put("delta", delta(lessonId));
        return m;
    }

    /**
     * 概念レッスンの★（クイズ全問正解）を記録し、応答へ問題クリアと同じ項目を加える。
     *
     * <p>報酬は1つの通知にまとめる。この回のクイズのチップは {@code rewardQuiz} が既に
     * 払っているので、★と章クリアのぶんを足した合計を {@code cafeAward} として返す
     * （通知を2つ出すと、同じ操作の結果が2回流れて何が起きたか読めなくなる）。
     */
    private void addConceptClearRewards(Map<String, Object> m, Curriculum c, Lesson lesson,
                                        ProgressStore.CafeAward quizAward) {
        ProgressStore.CafeAward starAward = addClearRewards(
                m, c, lesson, false, lesson.id(), Lesson.CONCEPT_TASK_ID, lesson.conceptKey());
        m.put("cafeAward", CafeApi.awardJson(quizAward.plus(starAward)));
    }


    /**
     * 復習の状態（苦手度と次の期限）を問題のJSONへ入れる。
     *
     * 期限はクリア済みの問題だけに意味があるので、未クリアでは載せない
     * （画面は「クリア済みのうち期限が来たもの」から出題を決める）。
     */
    private void putReviewState(Map<String, Object> target, String key, boolean cleared) {
        target.put("reviewWeight", progress.reviewWeight(key));
        if (!cleared) {
            return;
        }
        ProgressStore.ReviewDue due = progress.reviewDue(key);
        target.put("reviewLevel", due.level());
        target.put("reviewDue", due.dueDate());
        target.put("reviewDueDays", due.daysUntilDue());
    }
    /** 削除済み教材の古い進捗キーを数えず、現在の教材だけのクリア数を返す。 */
    private static int currentCurriculumClearedTaskCount(
            Curriculum c, Set<String> cleared) {
        int count = 0;
        for (Chapter chapter : c.chapters()) {
            count += c.clearedCount(chapter, cleared);
        }
        return count;
    }

    /** 章に属する全問題のキー。達成条件（ヒントなし制覇・1日制覇）の判定に渡す。 */
    private static List<String> chapterTaskKeys(Chapter chapter) {
        List<String> keys = new ArrayList<>();
        for (Lesson lesson : chapter.lessons()) {
            keys.addAll(lesson.taskKeys());
        }
        return keys;
    }

    private Object doHint(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        int index = MiniJson.intOf(body, "index", 0);
        Task task = requireTask(lessonId, taskId);
        if (index < 0 || index >= task.hints().size()) {
            throw new BadRequest("そのヒントはありません");
        }
        int revealed = progress.revealHint(Lesson.taskKey(lessonId, taskId), index);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lessonId", lessonId);
        m.put("taskId", taskId);
        m.put("index", index);
        m.put("text", task.hints().get(index));
        m.put("hintsRevealed", revealed);
        m.put("hintCount", task.hints().size());
        m.put("solutionUnlocked", solutionUnlocked(lessonId, task, progress.clearedIds()));
        return m;
    }

    /**
     * 問題のブックマークを付け外しする。
     *
     * 進捗の差分（{@link #delta(String)}）は返さない。ブックマークは1問だけの切り替えなので、
     * 全問題ぶんの差分を送り返す必要がない（画面側は手元のその1件を直す）。
     */
    private Object doBookmark(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        requireTask(lessonId, taskId);   // 知らないIDで progress.json を汚さない
        boolean bookmarked = progress.toggleBookmark(Lesson.taskKey(lessonId, taskId));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lessonId", lessonId);
        m.put("taskId", taskId);
        m.put("bookmarked", bookmarked);
        return m;
    }

    private Object doSolution(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        Task task = requireTask(lessonId, taskId);
        if (!solutionUnlocked(lessonId, task, progress.clearedIds())) {
            throw new BadRequest("模範解答はまだ見られません。先にヒントを全部見てみましょう。");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lessonId", lessonId);
        m.put("taskId", taskId);
        if (task.isMultiFile()) {
            m.put("files", task.isProject()
                    ? task.project().solutionFilesJson() : task.runtimeLab().solutionFilesJson());
        } else {
            m.put("solution", task.isArtifact()
                    ? task.solution()
                    : JavaSnippetFormatter.formatIfCompact(task.solution()));
        }
        return m;
    }

    /** 模範解答は「クリア済み」か「ヒントを全部見た」場合に開放する（問題ごと）。 */
    private boolean solutionUnlocked(String lessonId, Task task, Set<String> cleared) {
        if (!task.hasSolution()) {
            return false;
        }
        String key = Lesson.taskKey(lessonId, task.id());
        if (cleared.contains(key)) {
            return true;
        }
        return progress.hintsRevealed(key) >= task.hints().size();
    }

    /**
     * そのレッスンの同梱ライブラリ。レッスンIDが空・不明なら空リスト。
     *
     * 不明なIDでも例外にしないのは、サンプルの試し打ちのように「ライブラリが無くても
     * 実行できる」呼び方があるため。ライブラリが要るコードならコンパイルの時点で落ちる。
     */
    private List<SourceFile> libSourcesOf(String lessonId) {
        if (lessonId == null || lessonId.isEmpty()) {
            return List.of();
        }
        return curriculum.get().lesson(lessonId)
                .map(Lesson::libSources)
                .orElse(List.of());
    }

    private Task requireTask(String lessonId, String taskId) {
        Lesson lesson = curriculum.get().lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        return lesson.task(taskId)
                .orElseThrow(() -> new BadRequest("知らない問題です: " + lessonId + "#" + taskId));
    }

    /** リクエストの taskId。1レッスン1問だった頃のクライアントでも動くよう既定は1問目。 */
    private static String taskId(Map<String, Object> body) {
        return MiniJson.str(body, "taskId", "1");
    }

    /**
     * 教材が変わっていれば読み直す。
     *
     * 「編集したら再読み込みだけで反映される」ことは保ちつつ、変わっていない回は
     * {@code stat} だけで済ませる（解析は1回0.3〜0.4秒かかる。{@link ContentLoader#fingerprint()}）。
     */
    private void reloadContent() {
        long stamp = loader.fingerprint();
        if (stamp != 0 && stamp == contentStamp.get()) {
            return;
        }
        try {
            curriculum.set(loader.load());
            contentStamp.set(stamp);
        } catch (RuntimeException e) {
            // コンテンツを編集中で壊れている場合は、直前に読めたものを使い続ける。
            // 印は落としておく ― 直した瞬間に読み直せるようにするため
            contentStamp.set(0);
            System.err.println("コンテンツを読み直せませんでした（前回の内容を使います）: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- plumbing

    private static List<Object> diagnosticsJson(List<Diagnostic> diagnostics) {
        List<Object> list = new ArrayList<>(diagnostics.size());
        for (Diagnostic d : diagnostics) {
            list.add(d.toJson());
        }
        return list;
    }

    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream is = exchange.getRequestBody()) {
            raw = is.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new BadRequest("リクエストが大きすぎます");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return MiniJson.parseObject(text);
        } catch (RuntimeException e) {
            throw new BadRequest("JSONとして読めません: " + e.getMessage());
        }
    }

    /**
     * 実行・保存に渡すコード。長さの上限は {@link JavaRunner#SOURCE_LIMIT_CHARS} に合わせる。
     *
     * 上限を超えたコードはそもそもコンパイルできないので、保存だけ通しても意味がない。
     * それどころか progress.json は保存のたびに全体を書き直すので、巨大な下書きが
     * 混ざると以降の自動保存がすべて重くなる。受け取る前にここで断る。
     */
    private static String requireCode(Map<String, Object> body) {
        String code = requireString(body, "code");
        if (code.length() > JavaRunner.SOURCE_LIMIT_CHARS) {
            throw new BadRequest("コードが長すぎます（上限 " + JavaRunner.SOURCE_LIMIT_CHARS
                    + " 文字、送られたのは " + code.length() + " 文字）。");
        }
        return code;
    }

    /** project/runtime-lab問題の編集対象だけを、教材で宣言された順序にそろえて受け取る。 */
    private static Map<String, String> requireProjectFiles(Map<String, Object> body, Task task) {
        Object value = body.get("files");
        if (!(value instanceof Map<?, ?>)) {
            throw new BadRequest("\"files\" が必要です");
        }
        Map<String, Object> raw = MiniJson.asObj(value);
        List<String> expected = task.workspace().editableFiles().stream()
                .map(jq.content.ProjectFile::path).toList();
        if (!raw.keySet().equals(new java.util.LinkedHashSet<>(expected))) {
            throw new BadRequest("編集対象ファイルが一致しません。画面を再読み込みしてください。");
        }
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0;
        for (String path : expected) {
            Object content = raw.get(path);
            if (!(content instanceof String text)) {
                throw new BadRequest(path + " の内容が文字列ではありません");
            }
            if (text.length() > ProjectRunner.FILE_LIMIT_CHARS) {
                throw new BadRequest(path + " が長すぎます（上限 "
                        + ProjectRunner.FILE_LIMIT_CHARS + "文字）");
            }
            total += text.length();
            files.put(path, text);
        }
        if (total > ProjectRunner.TOTAL_LIMIT_CHARS) {
            throw new BadRequest("project全体が長すぎます（上限 "
                    + ProjectRunner.TOTAL_LIMIT_CHARS + "文字）");
        }
        return files;
    }

    /** 保存済みJSONが壊れていても、教材側の初期ファイルへ戻れるよう安全な分だけ読む。 */
    private static Map<String, Object> savedProjectFiles(Task task, String saved) {
        if (saved == null || saved.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = MiniJson.parseObject(saved);
            Map<String, Object> result = new LinkedHashMap<>();
            for (jq.content.ProjectFile file : task.workspace().editableFiles()) {
                Object content = raw.get(file.path());
                if (content instanceof String text && text.length() <= ProjectRunner.FILE_LIMIT_CHARS) {
                    result.put(file.path(), text);
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    static String requireString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (!(v instanceof String s)) {
            throw new BadRequest("\"" + key + "\" が必要です");
        }
        return s;
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = MiniJson.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        StaticHandler.send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    /**
     * コード実行のスロットを1つ確保してから実行する。
     *
     * 空くまで少しだけ待ち、それでも空かなければ 503 で断る。ここで無制限に待つと
     * 待っているリクエストがスレッドを抱えたままになり、絞った意味がなくなる。
     */
    private <T> T inRunSlot(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = runSlots.tryAcquire(RUN_SLOT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy("実行を中断しました。もう一度試してください。");
        }
        if (!acquired) {
            throw new Busy("いま別のコードの実行で混み合っています（同時に実行できるのは "
                    + MAX_CONCURRENT_RUNS + " 件までです）。少し待ってからもう一度試してください。");
        }
        try {
            return work.get();
        } finally {
            runSlots.release();
        }
    }

    /** 実行が混み合っていて受けられないときに投げる。 */
    private static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Busy(String message) {
            super(message);
        }
    }
}
