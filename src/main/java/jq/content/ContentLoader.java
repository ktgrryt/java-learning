package jq.content;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * content/manifest.json と各章のJSONを読み込んで {@link Curriculum} を組み立てる。
 *
 * 章を追加したいときは content/ にJSONを置き、manifest.json で所属する編の
 * "chapters" にファイル名を足すだけでよい（Javaコードの変更は不要）。
 *
 * Jakarta EE の章のように、学習者のコードと一緒にコンパイルしたい同梱ライブラリがある場合は、
 * ソースを {@code content/lib/<名前>/} に置き、章またはレッスンの {@code "libs"} に名前を書く。
 */
public final class ContentLoader {

    /** libs に書ける名前。ディレクトリ区切りやドットを許さないので、content/lib の外へは出られない。 */
    private static final Pattern LIB_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path contentDir;

    public ContentLoader(Path contentDir) {
        this.contentDir = contentDir;
    }

    public Curriculum load() {
        Path manifestPath = contentDir.resolve("manifest.json");
        Map<String, Object> manifest = MiniJson.parseObject(read(manifestPath));

        // 同梱ライブラリは章をまたいで共有されるので、1回の load 中は読み直さない。
        // （load() は /api/state ごとに呼ばれる。ライブラリを使うレッスンの数だけ
        //   同じファイルを読むのは無駄なので、ここで1回に畳む）
        Map<String, List<SourceFile>> libCache = new HashMap<>();

        List<CurriculumPart> parts = new ArrayList<>();
        int number = 1;
        List<Object> partEntries = MiniJson.list(manifest, "parts");
        if (partEntries.isEmpty()) {
            // 以前のmanifestも読み込めるようにする。新規追加ではpartsを使う。
            List<Chapter> chapters = new ArrayList<>();
            for (Object entry : MiniJson.list(manifest, "chapters")) {
                if (!(entry instanceof String fileName)) {
                    throw new IllegalStateException("manifest.json の chapters は文字列の配列にしてください");
                }
                chapters.add(parseChapterFile(fileName, "main", number, number, libCache));
                number++;
            }
            if (!chapters.isEmpty()) {
                parts.add(new CurriculumPart("main", "カリキュラム", "", "📚", List.copyOf(chapters)));
            }
        } else {
            for (Object partEntry : partEntries) {
                Map<String, Object> rawPart = MiniJson.asObj(partEntry);
                String partId = MiniJson.requireStr(rawPart, "id");
                List<Chapter> chapters = new ArrayList<>();
                int partNumber = 1;
                for (Object entry : MiniJson.list(rawPart, "chapters")) {
                    if (!(entry instanceof String fileName)) {
                        throw new IllegalStateException("manifest.json の parts[].chapters は文字列の配列にしてください");
                    }
                    chapters.add(parseChapterFile(fileName, partId, number++, partNumber++, libCache));
                }
                if (chapters.isEmpty()) {
                    throw new IllegalStateException("編 " + partId + " に章がありません");
                }
                parts.add(new CurriculumPart(
                        partId,
                        MiniJson.requireStr(rawPart, "title"),
                        MiniJson.str(rawPart, "subtitle", ""),
                        MiniJson.str(rawPart, "emoji", "📚"),
                        List.copyOf(chapters)));
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("章が1つも読み込めませんでした (" + contentDir.toAbsolutePath() + ")");
        }
        return new Curriculum(parts);
    }

    private Chapter parseChapterFile(String fileName, String partId, int number, int partNumber,
                                     Map<String, List<SourceFile>> libCache) {
        Path chapterPath = contentDir.resolve(fileName);
        try {
            return parseChapter(MiniJson.parseObject(read(chapterPath)), partId, number, partNumber, libCache);
        } catch (RuntimeException e) {
            throw new IllegalStateException(fileName + " を読めません: " + e.getMessage(), e);
        }
    }

    private Chapter parseChapter(Map<String, Object> raw, String partId, int number, int partNumber,
                                 Map<String, List<SourceFile>> libCache) {
        String chapterId = MiniJson.requireStr(raw, "id");
        // 章に書いた libs は、その章の全レッスンが受け継ぐ（レッスンごとに書き写さなくてよい）
        List<String> chapterLibs = stringList(raw, "libs");

        List<Lesson> lessons = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "lessons")) {
            lessons.add(parseLesson(MiniJson.asObj(o), chapterId, chapterLibs, libCache));
        }
        if (lessons.isEmpty()) {
            throw new IllegalStateException("章 " + chapterId + " にレッスンがありません");
        }
        return new Chapter(
                chapterId,
                partId,
                number,
                partNumber,
                MiniJson.requireStr(raw, "title"),
                MiniJson.str(raw, "subtitle", ""),
                MiniJson.str(raw, "emoji", "📘"),
                List.copyOf(lessons));
    }

    private Lesson parseLesson(Map<String, Object> raw, String chapterId, List<String> chapterLibs,
                               Map<String, List<SourceFile>> libCache) {
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
                parseQuizzes(raw, id),
                resolveLibs(chapterLibs, stringList(raw, "libs"), id, libCache));
    }

    /**
     * 章とレッスンの {@code libs} を合わせて、コンパイルに渡すソース一覧にする。
     *
     * 同じライブラリを章とレッスンの両方に書いても1回しか読まない。別のライブラリが
     * 同じ相対パスのファイルを持っていたら、どちらが勝つか分からなくなるのでエラーにする。
     */
    private List<SourceFile> resolveLibs(List<String> chapterLibs, List<String> lessonLibs,
                                         String lessonId, Map<String, List<SourceFile>> libCache) {
        LinkedHashSet<String> names = new LinkedHashSet<>(chapterLibs);
        names.addAll(lessonLibs);
        if (names.isEmpty()) {
            return List.of();
        }

        Map<String, SourceFile> byPath = new LinkedHashMap<>();
        Map<String, String> ownerOf = new LinkedHashMap<>();
        for (String name : names) {
            for (SourceFile sf : libSources(name, lessonId, libCache)) {
                String previous = ownerOf.put(sf.path(), name);
                if (previous != null) {
                    throw new IllegalStateException("レッスン " + lessonId + ": 同梱ライブラリ \""
                            + previous + "\" と \"" + name + "\" が同じファイル " + sf.path()
                            + " を持っています。どちらか片方にしてください");
                }
                byPath.put(sf.path(), sf);
            }
        }
        return List.copyOf(byPath.values());
    }

    /** content/lib/&lt;name&gt;/ 以下の .java を全部読む。 */
    private List<SourceFile> libSources(String name, String lessonId,
                                        Map<String, List<SourceFile>> cache) {
        List<SourceFile> cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        if (!LIB_NAME.matcher(name).matches()) {
            throw new IllegalStateException("レッスン " + lessonId + " の libs に使えない名前があります: \""
                    + name + "\"（英数字とハイフン・アンダースコアだけ）");
        }
        Path dir = contentDir.resolve("lib").resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("レッスン " + lessonId + " の libs \"" + name
                    + "\" に対応するディレクトリがありません: " + dir.toAbsolutePath());
        }

        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            // 並び順を固定する（コンパイル単位の順番が実行ごとに変わると原因を追いにくい）
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                files.add(new SourceFile(dir.relativize(p).toString().replace('\\', '/'), read(p)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("同梱ライブラリを読めません: " + dir.toAbsolutePath(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("同梱ライブラリ \"" + name + "\" に .java がありません: "
                    + dir.toAbsolutePath());
        }

        List<SourceFile> result = List.copyOf(files);
        cache.put(name, result);
        return result;
    }

    private static List<String> stringList(Map<String, Object> raw, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : MiniJson.list(raw, key)) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
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
