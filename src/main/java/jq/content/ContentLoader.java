package jq.content;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * content/manifest.json と各章のJSONを読み込んで {@link Curriculum} を組み立てる。
 *
 * 章を追加したいときは content/ にJSONを置き、manifest.json の "chapters" に
 * ファイル名を足すだけでよい（Javaコードの変更は不要）。
 */
public final class ContentLoader {

    private final Path contentDir;

    public ContentLoader(Path contentDir) {
        this.contentDir = contentDir;
    }

    public Curriculum load() {
        Path manifestPath = contentDir.resolve("manifest.json");
        Map<String, Object> manifest = MiniJson.parseObject(read(manifestPath));

        List<Chapter> chapters = new ArrayList<>();
        int number = 1;
        for (Object entry : MiniJson.list(manifest, "chapters")) {
            if (!(entry instanceof String fileName)) {
                throw new IllegalStateException("manifest.json の chapters は文字列の配列にしてください");
            }
            Path chapterPath = contentDir.resolve(fileName);
            try {
                chapters.add(parseChapter(MiniJson.parseObject(read(chapterPath)), number++));
            } catch (RuntimeException e) {
                throw new IllegalStateException(fileName + " を読めません: " + e.getMessage(), e);
            }
        }
        if (chapters.isEmpty()) {
            throw new IllegalStateException("章が1つも読み込めませんでした (" + contentDir.toAbsolutePath() + ")");
        }
        return new Curriculum(chapters);
    }

    private Chapter parseChapter(Map<String, Object> raw, int number) {
        String chapterId = MiniJson.requireStr(raw, "id");
        List<Lesson> lessons = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "lessons")) {
            lessons.add(parseLesson(MiniJson.asObj(o), chapterId));
        }
        if (lessons.isEmpty()) {
            throw new IllegalStateException("章 " + chapterId + " にレッスンがありません");
        }
        return new Chapter(
                chapterId,
                number,
                MiniJson.requireStr(raw, "title"),
                MiniJson.str(raw, "subtitle", ""),
                MiniJson.str(raw, "emoji", "📘"),
                List.copyOf(lessons));
    }

    private Lesson parseLesson(Map<String, Object> raw, String chapterId) {
        String id = MiniJson.requireStr(raw, "id");

        List<Sample> samples = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "samples")) {
            Map<String, Object> s = MiniJson.asObj(o);
            samples.add(new Sample(
                    MiniJson.str(s, "caption", "サンプル"),
                    MiniJson.requireStr(s, "code"),
                    MiniJson.str(s, "stdin", "")));
        }

        return new Lesson(
                id,
                chapterId,
                MiniJson.requireStr(raw, "title"),
                MiniJson.str(raw, "explanation", ""),
                List.copyOf(samples),
                parseTasks(raw, id),
                parseQuizzes(raw, id));
    }

    /**
     * レッスンの練習問題を読む。
     *
     * 1問目はレッスン直下の task / starterCode / … に書く（レッスンが1問だけなら
     * これで完結する）。2問目以降は "extraTasks" 配列に足す。
     *
     * 問題のIDは並び順の1始まりの連番で、進捗の保存キー（レッスンID#連番）になる。
     * だから extraTasks は **末尾に足す**（途中に挿入すると連番がずれて、
     * すでにクリアした問題が別の問題として扱われてしまう）。
     */
    private List<Task> parseTasks(Map<String, Object> raw, String lessonId) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(parseTask(raw, lessonId, 1, "practice"));
        for (Object o : MiniJson.list(raw, "extraTasks")) {
            tasks.add(parseTask(MiniJson.asObj(o), lessonId, tasks.size() + 1, "drill"));
        }
        return List.copyOf(tasks);
    }

    private Task parseTask(Map<String, Object> raw, String lessonId, int number, String defaultKind) {
        String id = String.valueOf(number);
        String where = "レッスン " + lessonId + " の問題" + number;

        List<TestCase> cases = new ArrayList<>();
        collectCases(cases, MiniJson.list(raw, "visibleCases"), false);
        collectCases(cases, MiniJson.list(raw, "hiddenCases"), true);
        if (cases.isEmpty()) {
            throw new IllegalStateException(where + " にテストケースがありません");
        }

        List<String> hints = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "hints")) {
            if (o instanceof String s) {
                hints.add(s);
            }
        }

        String kind = MiniJson.str(raw, "kind", defaultKind);
        if (!kind.equals("practice") && !kind.equals("drill") && !kind.equals("applied")) {
            throw new IllegalStateException(where + " の kind は practice / drill / applied "
                    + "のいずれかにしてください: " + kind);
        }

        return new Task(
                id,
                kind,
                MiniJson.str(raw, "task", ""),
                MiniJson.str(raw, "starterCode", defaultStarter()),
                List.copyOf(cases),
                List.copyOf(hints),
                MiniJson.str(raw, "solution", ""));
    }

    /**
     * 選択式クイズを読む（任意。無ければ空リスト）。
     *
     * 正解の番号が選択肢の範囲外だと「絶対に正解できない問題」になってしまうので、
     * ここで弾いて起動時に気づけるようにする。
     */
    private List<Quiz> parseQuizzes(Map<String, Object> raw, String lessonId) {
        List<Quiz> quizzes = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "quiz")) {
            Map<String, Object> q = MiniJson.asObj(o);

            List<String> choices = new ArrayList<>();
            for (Object c : MiniJson.list(q, "choices")) {
                if (c instanceof String s) {
                    choices.add(s);
                }
            }
            if (choices.size() < 2) {
                throw new IllegalStateException(
                        "レッスン " + lessonId + " のクイズには choices が2つ以上必要です");
            }

            int answer = MiniJson.intOf(q, "answer", -1);
            if (answer < 0 || answer >= choices.size()) {
                throw new IllegalStateException("レッスン " + lessonId
                        + " のクイズの answer が選択肢の範囲外です: " + answer);
            }

            quizzes.add(new Quiz(
                    MiniJson.requireStr(q, "question"),
                    List.copyOf(choices),
                    answer,
                    MiniJson.str(q, "explanation", "")));
        }
        return List.copyOf(quizzes);
    }

    private void collectCases(List<TestCase> out, List<Object> raw, boolean hidden) {
        int index = 1;
        for (Object o : raw) {
            Map<String, Object> c = MiniJson.asObj(o);
            String fallbackLabel = (hidden ? "隠しケース" : "ケース") + index++;
            out.add(new TestCase(
                    MiniJson.str(c, "label", fallbackLabel),
                    MiniJson.str(c, "stdin", ""),
                    MiniJson.str(c, "expected", ""),
                    hidden));
        }
    }

    private static String defaultStarter() {
        return """
                public class Main {
                    public static void main(String[] args) {
                        // ここにコードを書こう
                    }
                }
                """;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("読み込めません: " + path.toAbsolutePath(), e);
        }
    }
}
