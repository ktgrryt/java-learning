package jq.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.Lesson;
import jq.content.TestCase;
import jq.judge.CaseResult;
import jq.judge.Judge;
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
                case "/api/solution" -> sendJson(exchange, 200, doSolution(body));
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

            @SuppressWarnings("unchecked")
            List<Object> lessons = (List<Object>) chJson.get("lessons");
            for (Object lo : lessons) {
                @SuppressWarnings("unchecked")
                Map<String, Object> lJson = (Map<String, Object>) lo;
                String id = (String) lJson.get("id");
                Lesson lesson = c.lesson(id).orElseThrow();
                lJson.put("cleared", cleared.contains(id));
                lJson.put("savedCode", progress.savedCode(id));
                lJson.put("hintsRevealed", progress.hintsRevealed(id));
                lJson.put("solutionUnlocked", solutionUnlocked(lesson, cleared));
            }
            chapters.add(chJson);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapters", chapters);
        m.put("progress", progress.toJson());
        m.put("totalLessons", c.totalLessonCount());
        return m;
    }

    /** 採点なしで1回実行する。「まず動かしてみる」ためのボタン。 */
    private Object doRun(Map<String, Object> body) {
        String code = requireString(body, "code");
        String stdin = MiniJson.str(body, "stdin", "");
        String lessonId = MiniJson.str(body, "lessonId", "");
        if (!lessonId.isEmpty()) {
            progress.saveCode(lessonId, code);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try (JavaRunner.Compiled compiled = runner.compile(code)) {
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

    /** 全テストケースで採点する。全部通ったら★を付ける。 */
    private Object doSubmit(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        String code = requireString(body, "code");
        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));

        progress.saveCode(lessonId, code);
        int attempts = progress.recordAttempt(lessonId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lessonId", lessonId);
        result.put("attempts", attempts);

        try (JavaRunner.Compiled compiled = runner.compile(code)) {
            result.put("compiled", compiled.success());
            result.put("diagnostics", diagnosticsJson(compiled.diagnostics()));
            if (!compiled.success()) {
                result.put("allPass", false);
                result.put("cases", List.of());
                result.put("state", state());
                return result;
            }

            // コンパイルは1回だけ。あとは標準入力を変えて必要な回数だけ走らせる
            List<Object> cases = new ArrayList<>();
            int passed = 0;
            for (TestCase tc : lesson.allCases()) {
                RunResult run = runner.run(compiled, tc.stdin());
                CaseResult cr = Judge.judge(tc, run);
                if (cr.pass()) {
                    passed++;
                }
                cases.add(cr.toJson());
            }
            boolean allPass = passed == lesson.allCases().size();
            result.put("cases", cases);
            result.put("passedCount", passed);
            result.put("allPass", allPass);

            if (allPass) {
                Set<String> before = progress.clearedIds();
                Chapter chapter = c.chapterOf(lessonId);
                boolean chapterWasCleared = c.isChapterCleared(chapter, before);

                boolean firstTime = progress.markCleared(lessonId);
                Set<String> after = progress.clearedIds();

                result.put("newStar", firstTime);
                result.put("chapterCleared", !chapterWasCleared && c.isChapterCleared(chapter, after));
                result.put("chapterTitle", chapter.title());
                result.put("chapterNumber", chapter.number());
                result.put("nextLessonId", c.nextLessonId(lessonId));
                result.put("allChaptersCleared", after.size() == c.totalLessonCount());
            }
        }
        result.put("state", state());
        return result;
    }

    private Object doSave(Map<String, Object> body) {
        String lessonId = requireString(body, "lessonId");
        progress.saveCode(lessonId, requireString(body, "code"));
        return Map.of("ok", true);
    }

    private Object doHint(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        int index = MiniJson.intOf(body, "index", 0);
        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        if (index < 0 || index >= lesson.hints().size()) {
            throw new BadRequest("そのヒントはありません");
        }
        int revealed = progress.revealHint(lessonId, index);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("text", lesson.hints().get(index));
        m.put("hintsRevealed", revealed);
        m.put("hintCount", lesson.hints().size());
        m.put("solutionUnlocked", solutionUnlocked(lesson, progress.clearedIds()));
        return m;
    }

    private Object doSolution(Map<String, Object> body) {
        Curriculum c = curriculum.get();
        String lessonId = requireString(body, "lessonId");
        Lesson lesson = c.lesson(lessonId)
                .orElseThrow(() -> new BadRequest("知らないレッスンです: " + lessonId));
        if (!solutionUnlocked(lesson, progress.clearedIds())) {
            throw new BadRequest("模範解答はまだ見られません。先にヒントを全部見てみましょう。");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lessonId", lessonId);
        m.put("solution", lesson.solution());
        return m;
    }

    /** 模範解答は「クリア済み」か「ヒントを全部見た」場合に開放する。 */
    private boolean solutionUnlocked(Lesson lesson, Set<String> cleared) {
        if (lesson.solution().isEmpty()) {
            return false;
        }
        if (cleared.contains(lesson.id())) {
            return true;
        }
        return progress.hintsRevealed(lesson.id()) >= lesson.hints().size();
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
