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

        List<TestCase> cases = new ArrayList<>();
        collectCases(cases, MiniJson.list(raw, "visibleCases"), false);
        collectCases(cases, MiniJson.list(raw, "hiddenCases"), true);
        if (cases.isEmpty()) {
            throw new IllegalStateException("レッスン " + id + " にテストケースがありません");
        }

        List<String> hints = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "hints")) {
            if (o instanceof String s) {
                hints.add(s);
            }
        }

        return new Lesson(
                id,
                chapterId,
                MiniJson.requireStr(raw, "title"),
                MiniJson.str(raw, "explanation", ""),
                List.copyOf(samples),
                MiniJson.str(raw, "task", ""),
                MiniJson.str(raw, "starterCode", defaultStarter()),
                List.copyOf(cases),
                List.copyOf(hints),
                MiniJson.str(raw, "solution", ""));
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
