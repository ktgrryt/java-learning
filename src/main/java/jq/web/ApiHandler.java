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
import jq.judge.Judge;
import jq.judge.SourceChecker;
import jq.json.MiniJson;
import jq.progress.ProgressStore;
import jq.runner.Diagnostic;
import jq.runner.JavaRunner;
import jq.runner.RunResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>{@code POST /api/cafe/purchase} … カフェ設備を購入</li>
 *   <li>{@code POST /api/cafe/automation/purchase} … 自動営業設備を購入</li>
 *   <li>{@code POST /api/cafe/passive/*} … 画面表示中の自動売上を開始・精算・停止</li>
 *   <li>{@code POST /api/cafe/item/purchase} … スペシャルアイテムを購入</li>
 *   <li>{@code POST /api/cafe/items/seen} … 解放済みアイテムの通知を既読にする</li>
 *   <li>{@code POST /api/cafe/expand} … カフェの店舗網を拡大</li>
 *   <li>{@code POST /api/reset}    … 進捗を全消去</li>
 * </ul>
 */
public final class ApiHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 200_000;

    private final ContentLoader loader;
    private final ProgressStore progress;
    private final JavaRunner runner = new JavaRunner();
    private final AtomicReference<Curriculum> curriculum = new AtomicReference<>();

    public ApiHandler(ContentLoader loader, ProgressStore progress) {
        this.loader = loader;
        this.progress = progress;
        this.curriculum.set(loader.load());
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
                case "/api/run" -> sendJson(exchange, 200, doRun(body));
                case "/api/submit" -> sendJson(exchange, 200, doSubmit(body));
                case "/api/save" -> sendJson(exchange, 200, doSave(body));
                case "/api/hint" -> sendJson(exchange, 200, doHint(body));
                case "/api/quiz" -> sendJson(exchange, 200, doQuiz(body));
                case "/api/solution" -> sendJson(exchange, 200, doSolution(body));
                case "/api/cafe/purchase" -> sendJson(exchange, 200, doCafePurchase(body));
                case "/api/cafe/automation/purchase" ->
                        sendJson(exchange, 200, doCafeAutomationPurchase(body));
                case "/api/cafe/passive/start" -> sendJson(exchange, 200, doCafePassive(body, "start"));
                case "/api/cafe/passive/collect" -> sendJson(exchange, 200, doCafePassive(body, "collect"));
                case "/api/cafe/passive/stop" -> sendJson(exchange, 200, doCafePassive(body, "stop"));
                case "/api/cafe/item/purchase" -> sendJson(exchange, 200, doCafeItemPurchase(body));
                case "/api/cafe/items/seen" -> sendJson(exchange, 200, doCafeItemsSeen());
                case "/api/cafe/expand" -> sendJson(exchange, 200, doCafeExpand());
                case "/api/reset" -> {
                    progress.resetAll();
                    sendJson(exchange, 200, state());
                }
                default -> sendError(exchange, 404, "そんなURLはありません: " + path);
            }
        } catch (BadRequest e) {
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "サーバ内部でエラーが起きました: " + e);
        } finally {
            exchange.close();
        }
    }

    // -------------------------------------------------------------- handlers

    /** カリキュラム全体と進捗をまとめて返す。画面はこれ1本で描ける。 */
    private Object state() {
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();

        List<Object> chapters = new ArrayList<>();
        for (Chapter ch : c.chapters()) {
            Map<String, Object> chJson = ch.toPublicJson();
            chJson.put("cleared", c.isChapterCleared(ch, cleared));
            chJson.put("clearedCount", c.clearedCount(ch, cleared));
            chJson.put("taskCount", c.taskCount(ch));

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

                @SuppressWarnings("unchecked")
                List<Object> tasks = (List<Object>) lJson.get("tasks");
                for (int i = 0; i < tasks.size(); i++) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tJson = (Map<String, Object>) tasks.get(i);
                    Task task = lesson.tasks().get(i);
                    String key = Lesson.taskKey(id, task.id());
                    tJson.put("cleared", cleared.contains(key));
                    tJson.put("savedCode", progress.savedCode(key));
                    tJson.put("hintsRevealed", progress.hintsRevealed(key));
                    tJson.put("revealedHints", revealedHints(id, task));
                    tJson.put("passedCount", progress.bestPassed(key));
                    tJson.put("solutionUnlocked", solutionUnlocked(id, task, cleared));
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
        m.put("progress", progress.toClientJson(cafeLearningProgress(c, cleared)));
        m.put("totalLessons", c.totalLessonCount());
        m.put("totalTasks", c.totalTaskCount());
        m.put("quizTotal", quizTotal);
        m.put("quizCorrect", quizCorrect);
        return m;
    }

    /**
     * 提出・クイズ回答のあとに返す差分。
     *
     * 全カリキュラムを返すと毎回500KB近くを送り直すことになる（解説やサンプルは
     * 何も変わっていないのに）。画面が描き直しに必要なのは進捗まわりだけなので、
     * それだけを返して、ブラウザ側は手元の state に上書きする。
     *
     * @param lessonId 影響を受けたレッスン（クイズの回答状況を返すため）。不要なら null
     */
    private Object delta(String lessonId) {
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();

        List<Object> chapters = new ArrayList<>();
        List<Object> lessons = new ArrayList<>();
        List<Object> tasks = new ArrayList<>();
        int quizCorrect = 0;
        for (Chapter ch : c.chapters()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", ch.id());
            cm.put("cleared", c.isChapterCleared(ch, cleared));
            cm.put("clearedCount", c.clearedCount(ch, cleared));
            chapters.add(cm);

            for (Lesson lesson : ch.lessons()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("id", lesson.id());
                lm.put("cleared", c.isLessonCleared(lesson, cleared));
                lm.put("clearedCount", c.clearedCount(lesson, cleared));
                lessons.add(lm);

                for (Task task : lesson.tasks()) {
                    String key = Lesson.taskKey(lesson.id(), task.id());
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("lessonId", lesson.id());
                    tm.put("taskId", task.id());
                    tm.put("cleared", cleared.contains(key));
                    tm.put("passedCount", progress.bestPassed(key));
                    tm.put("hintsRevealed", progress.hintsRevealed(key));
                    tm.put("solutionUnlocked", solutionUnlocked(lesson.id(), task, cleared));
                    tasks.add(tm);
                }
                quizCorrect += correctQuizCount(lesson);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("progress", progress.toClientJson(cafeLearningProgress(c, cleared)));
        m.put("quizCorrect", quizCorrect);
        m.put("chapters", chapters);
        m.put("lessons", lessons);
        m.put("tasks", tasks);
        if (lessonId != null) {
            Lesson lesson = c.lesson(lessonId).orElse(null);
            if (lesson != null) {
                m.put("lessonId", lessonId);
                m.put("quizResults", quizResults(lesson));
            }
        }
        return m;
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

    /** 全テストケースで採点する。全部通ったら★を付ける（★は問題ごと）。 */
    private Object doSubmit(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        String code = requireCode(body);
        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        Task task = lesson.task(taskId)
                .orElseThrow(() -> new BadRequest("知らない問題です: " + lessonId + "#" + taskId));
        String key = Lesson.taskKey(lessonId, taskId);

        progress.saveCode(key, code);
        int attempts = progress.recordAttempt(key);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lessonId", lessonId);
        result.put("taskId", taskId);
        result.put("attempts", attempts);

        try (JavaRunner.Compiled compiled = runner.compile(code, lesson.libSources())) {
            result.put("compiled", compiled.success());
            result.put("diagnostics", diagnosticsJson(compiled.diagnostics()));
            if (!compiled.success()) {
                result.put("allPass", false);
                result.put("cases", List.of());
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

            if (allPass) {
                Set<String> before = progress.clearedIds();
                Chapter chapter = c.chapterOf(lessonId);
                boolean chapterWasCleared = c.isChapterCleared(chapter, before);
                boolean lessonWasCleared = c.isLessonCleared(lesson, before);

                boolean firstTime = progress.markCleared(key);
                Set<String> after = progress.clearedIds();
                ProgressStore.CafeLearningProgress cafeLearningAfter =
                        cafeLearningProgress(c, after);

                boolean chapterCompletedNow = firstTime
                        && !chapterWasCleared
                        && c.isChapterCleared(chapter, after);
                ProgressStore.CafeAward cafeAward = ProgressStore.CafeAward.NONE;
                if (firstTime) {
                    cafeAward = progress.rewardTask(cafeLearningAfter);
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
                result.put("cafeAward", cafeAwardJson(cafeAward));
                Curriculum.TaskRef next = c.nextTask(lessonId, taskId);
                result.put("next", next == null ? null : next.toJson());
                result.put("allChaptersCleared", after.size() == c.totalTaskCount());
            }
        }
        result.put("delta", delta(lessonId));
        return result;
    }

    private Object doSave(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        String taskId = taskId(body);
        // 知らないレッスンIDでも保存できると、progress.json に無関係なキーをいくらでも
        // 積める（消す手立ては画面に無い）。実在する問題の下書きだけを受け付ける
        requireTask(lessonId, taskId);
        progress.saveCode(Lesson.taskKey(lessonId, taskId), requireCode(body));
        return Map.of("ok", true);
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
        progress.recordQuiz(lessonId, index, choice);
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
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
        m.put("cafeAward", cafeAwardJson(cafeAward));
        m.put("delta", delta(lessonId));
        return m;
    }

    private Object doCafePurchase(Map<String, Object> body) {
        String id = requireString(body, "id");
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();
        ProgressStore.CafeLearningProgress cafeLearning = cafeLearningProgress(c, cleared);
        ProgressStore.PurchaseResult purchase = progress.purchaseCafeUpgrade(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> upgrade = new LinkedHashMap<>();
        upgrade.put("id", purchase.upgrade().id());
        upgrade.put("name", purchase.upgrade().name());
        upgrade.put("emoji", purchase.upgrade().emoji());
        upgrade.put("tier", purchase.upgrade().tier());
        upgrade.put("replacedName", purchase.replacedUpgrade() == null
                ? null : purchase.replacedUpgrade().name());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upgrade", upgrade);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    private Object doCafeAutomationPurchase(Map<String, Object> body) {
        String id = requireString(body, "id");
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
        ProgressStore.AutomationPurchaseResult purchase = progress.purchaseCafeAutomation(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> automation = new LinkedHashMap<>();
        automation.put("id", purchase.automation().id());
        automation.put("name", purchase.automation().name());
        automation.put("emoji", purchase.automation().emoji());
        automation.put("tier", purchase.automation().tier());
        automation.put("replacedName", purchase.replacedAutomation() == null
                ? null : purchase.replacedAutomation().name());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("automation", automation);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    private Object doCafePassive(Map<String, Object> body, String action) {
        String sessionId = requireString(body, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > 100) {
            throw new BadRequest("自動売上のセッションIDが不正です");
        }
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
        ProgressStore.PassiveSalesResult passive = switch (action) {
            case "start" -> progress.startCafePassiveSales(
                    sessionId, cafeLearning.masteredChapterTasks());
            case "collect" -> progress.collectCafePassiveSales(
                    sessionId, cafeLearning.masteredChapterTasks());
            case "stop" -> progress.stopCafePassiveSales(
                    sessionId, cafeLearning.masteredChapterTasks());
            default -> throw new IllegalArgumentException("unknown passive action: " + action);
        };

        Map<String, Object> passiveJson = new LinkedHashMap<>();
        passiveJson.put("cash", passive.cash());
        passiveJson.put("cashPerMinute", passive.cashPerMinute());
        passiveJson.put("active", passive.active());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passive", passiveJson);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    private Object doCafeExpand() {
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
        ProgressStore.ExpansionResult expansion = progress.expandCafeNetwork();
        if (!expansion.expanded()) {
            throw new BadRequest(expansion.error());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previousStores", expansion.previousStores());
        result.put("addedStores", expansion.addedStores());
        result.put("storeCount", expansion.storeCount());
        result.put("cost", expansion.cost());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expansion", result);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    private Object doCafeItemPurchase(Map<String, Object> body) {
        String id = requireString(body, "id");
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
        ProgressStore.ItemPurchaseResult purchase = progress.purchaseCafeItem(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", purchase.item().id());
        item.put("name", purchase.item().name());
        item.put("emoji", purchase.item().emoji());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    private Object doCafeItemsSeen() {
        Curriculum c = curriculum.get();
        progress.markCafeItemsSeen();
        ProgressStore.CafeLearningProgress cafeLearning =
                cafeLearningProgress(c, progress.clearedIds());
        return Map.of("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
    }

    /** 制覇した章数と、その章に含まれる問題数。短い章だけの先取りを有利にしない。 */
    private ProgressStore.CafeLearningProgress cafeLearningProgress(
            Curriculum c, Set<String> cleared) {
        int chapterCount = 0;
        int masteredChapterTasks = 0;
        for (Chapter chapter : c.chapters()) {
            if (c.isChapterCleared(chapter, cleared)) {
                chapterCount++;
                masteredChapterTasks += c.taskCount(chapter);
            }
        }
        return new ProgressStore.CafeLearningProgress(chapterCount, masteredChapterTasks);
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
        m.put("solution", JavaSnippetFormatter.formatIfCompact(task.solution()));
        return m;
    }

    /** 模範解答は「クリア済み」か「ヒントを全部見た」場合に開放する（問題ごと）。 */
    private boolean solutionUnlocked(String lessonId, Task task, Set<String> cleared) {
        if (task.solution().isEmpty()) {
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

    private void reloadContent() {
        try {
            curriculum.set(loader.load());
        } catch (RuntimeException e) {
            // コンテンツを編集中で壊れている場合は、直前に読めたものを使い続ける
            System.err.println("コンテンツを読み直せませんでした（前回の内容を使います）: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- plumbing

    private static Object cafeAwardJson(ProgressStore.CafeAward award) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cash", award.cash());
        m.put("cups", award.cups());
        m.put("itemEvents", award.itemEvents());
        return m;
    }

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

    private static String requireString(Map<String, Object> body, String key) {
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

    /** クライアント側の入力が不正なときに投げる。 */
    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }
}
