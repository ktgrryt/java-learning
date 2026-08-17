package jq.content;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
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

    /** 概念レッスンに要るクイズの数。★の根拠がクイズだけなので、少ないと当て推量で★が付く。 */
    private static final int CONCEPT_MIN_QUIZZES = 3;
    /** libs に書ける名前。ディレクトリ区切りやドットを許さないので、content/lib の外へは出られない。 */
    private static final Pattern LIB_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SAFE_PROJECT_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    // `jcmd` と `jfr` はJDK付属の診断ツール。`jfr` は在るだけでは足りないので、
    // PreflightRunner が短い記録を実際に作って確かめる（OpenJ9系は作れない）。
    private static final java.util.Set<String> PREFLIGHT_TOOLS = java.util.Set.of(
            "java", "javac", "jcmd", "jfr", "maven", "gradle", "docker", "docker-or-podman");
    private static final java.util.Set<String> RUNTIME_CAPABILITIES = java.util.Set.of(
            "server", "db", "http", "jfr", "container", "jdk-tool", "build");
    private static final java.util.Set<String> RUNTIME_TOOLS = java.util.Set.of(
            "java", "javac", "javap", "jdeps", "jlink", "jar", "jcmd", "jfr", "jshell", "jpackage",
            "keytool", "mvn", "gradle",
            "docker",
            "docker-or-podman");
    private static final Pattern RUNTIME_IMAGE = Pattern.compile("[A-Za-z0-9._/@:-]+");
    private static final List<String> PROJECT_GENERATED_DIRS = List.of(
            ".git", ".gradle", ".idea", ".liberty", ".quarkus",
            "build", "target", "out", "runtime", "node_modules");
    private static final List<String> PROJECT_TEXT_NAMES = List.of("Dockerfile", "pom.xml");
    private static final List<String> PROJECT_TEXT_EXTENSIONS = List.of(
            ".java", ".xml", ".properties", ".sql", ".md", ".txt", ".json",
            ".yaml", ".yml", ".gradle", ".kts", ".sh", ".options", ".jsh");

    private final Path contentDir;

    public ContentLoader(Path contentDir) {
        this.contentDir = contentDir;
    }

    /**
     * content/ の中身が変わったかを見分けるための印。
     *
     * <p>{@code /api/state} は「教材を編集したらブラウザの再読み込みだけで反映される」ように
     * 毎回 {@link #load()} を呼ぶ。ところが全章のJSONは4MBを超えており、解析だけで1回
     * 0.3〜0.4秒かかる（69ファイル）。編集していないときにこれを繰り返す意味はないので、
     * 読み直しが要るかをここで先に判断する。</p>
     *
     * <p>中身は読まない。パス・更新時刻・大きさだけを混ぜるので、138ファイルでも数ミリ秒で
     * 済む（{@code stat} だけ）。同梱ライブラリ（{@code content/lib/}）も含めて見るので、
     * ライブラリだけを直した場合も読み直しになる。</p>
     *
     * @return 中身が同じなら同じ値。<b>0 は「判断できなかった」</b>を表す
     *         （呼び出し側は毎回読み直す ― 分からないときは古いものを見せない側に倒す）
     */
    public long fingerprint() {
        try (Stream<Path> paths = Files.walk(contentDir.toRealPath())) {
            long mixed = 1L;
            for (Path path : paths.sorted().toList()) {
                mixed = mixed * 31 + path.toString().hashCode();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    mixed = mixed * 31 + Files.getLastModifiedTime(path).toMillis();
                    mixed = mixed * 31 + Files.size(path);
                }
            }
            return mixed == 0 ? 1 : mixed;   // 0 は「判断できなかった」に使うので避ける
        } catch (IOException | RuntimeException e) {
            return 0;
        }
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
                parts.add(new CurriculumPart(
                        "main", "カリキュラム", "", "", "📚", List.copyOf(chapters)));
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
                        MiniJson.str(rawPart, "prerequisite", ""),
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

        List<Objective> objectives = parseObjectives(raw, chapterId);
        java.util.Set<String> objectiveIds = new java.util.LinkedHashSet<>();
        for (Objective o : objectives) {
            objectiveIds.add(o.id());
        }

        List<Lesson> lessons = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "lessons")) {
            lessons.add(parseLesson(MiniJson.asObj(o), chapterId, chapterLibs, libCache, objectiveIds));
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
                List.copyOf(objectives),
                List.copyOf(lessons));
    }

    /**
     * 章の到達目標を読む（任意。無ければ空リスト）。
     *
     * <p>idの形と重複だけをここで弾く。「測る問題があるか」までは
     * {@code tools/check-objectives.sh} が見る（サーバーを立てずに数秒で分かるようにするため）。
     */
    private List<Objective> parseObjectives(Map<String, Object> raw, String chapterId) {
        List<Objective> objectives = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object entry : MiniJson.list(raw, "objectives")) {
            Map<String, Object> o = MiniJson.asObj(entry);
            String id = MiniJson.requireStr(o, "id");
            // 章のidは `ch03` のほか、旧来の `30` のような形も残っている。
            // 形を決め打ちせず、その章のid＋`-oM` を要求する。
            if (!id.matches(java.util.regex.Pattern.quote(chapterId) + "-o\\d+")) {
                throw new IllegalStateException("章 " + chapterId
                        + " の到達目標のidは「章のid + -oM」にしてください（例: " + chapterId + "-o1）: " + id);
            }
            if (!seen.add(id)) {
                throw new IllegalStateException("章 " + chapterId + " の到達目標のidが重複しています: " + id);
            }
            objectives.add(new Objective(id, MiniJson.requireStr(o, "text")));
        }
        return objectives;
    }

    /**
     * {@code objectiveIds} を読み、その章に無いIDなら起動時に止める。
     *
     * <p>黙って無視すると、画面には目標が出ているのにどの問題からも測られない状態が
     * 気づかれずに残る。他の不正参照と同じく、起動時に落として気づけるようにする。
     */
    private List<String> parseObjectiveIds(Map<String, Object> raw, String where,
                                           java.util.Set<String> known) {
        List<String> ids = new ArrayList<>();
        for (String id : stringList(raw, "objectiveIds")) {
            if (!known.contains(id)) {
                throw new IllegalStateException(where + " の objectiveIds が章の到達目標にありません: "
                        + id + "（章に書いたidは " + known + "）");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Lesson parseLesson(Map<String, Object> raw, String chapterId, List<String> chapterLibs,
                               Map<String, List<SourceFile>> libCache,
                               java.util.Set<String> objectiveIds) {
        String id = MiniJson.requireStr(raw, "id");
        // typeは1問目のartifact/project種別として既に使われているため、
        // レッスン自体の種類はlessonTypeへ分ける。
        String lessonType = MiniJson.str(raw, "lessonType", "lesson");
        if (!lessonType.equals("lesson") && !lessonType.equals("preflight")
                && !lessonType.equals("concept")) {
            throw new IllegalStateException("レッスン " + id
                    + " のtypeはlesson / preflight / conceptにしてください");
        }
        PreflightSpec preflight = lessonType.equals("preflight") ? parsePreflight(raw, id) : null;
        boolean concept = lessonType.equals("concept");
        if (concept) {
            checkConcept(raw, id);
        }

        List<Sample> samples = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "samples")) {
            Map<String, Object> s = MiniJson.asObj(o);
            samples.add(new Sample(
                    MiniJson.str(s, "caption", "サンプル"),
                    MiniJson.requireStr(s, "code"),
                    MiniJson.str(s, "stdin", ""),
                    s.containsKey("expected") ? MiniJson.str(s, "expected", "") : null));
        }

        return new Lesson(
                id,
                chapterId,
                MiniJson.requireStr(raw, "title"),
                MiniJson.str(raw, "explanation", ""),
                List.copyOf(samples),
                preflight == null && !concept ? parseTasks(raw, id, objectiveIds) : List.of(),
                parseQuizzes(raw, id),
                resolveLibs(chapterLibs, stringList(raw, "libs"), id, libCache),
                preflight,
                concept,
                List.copyOf(parseObjectiveIds(raw, "レッスン " + id, objectiveIds)));
    }

    /**
     * 概念レッスンの形を確かめる。
     *
     * 提出課題を書けるようにすると、概念レッスンにする理由（測る対象がずれる論点）が
     * 消えてしまうので、書いてあればエラーにする。逆にクイズは★の唯一の根拠なので、
     * 数が少ないと「たまたま当たった」で★が付く。3問以上を求める。
     */
    private void checkConcept(Map<String, Object> raw, String lessonId) {
        if (raw.containsKey("task") || !MiniJson.list(raw, "extraTasks").isEmpty()) {
            throw new IllegalStateException("概念レッスン " + lessonId
                    + " には問題を置けません（提出課題を付けるなら通常のレッスンにしてください）");
        }
        if (raw.containsKey("preflight")) {
            throw new IllegalStateException("概念レッスン " + lessonId + " には事前確認を置けません");
        }
        int quizzes = MiniJson.list(raw, "quiz").size();
        if (quizzes < CONCEPT_MIN_QUIZZES) {
            throw new IllegalStateException("概念レッスン " + lessonId + " のクイズが"
                    + quizzes + "問です（★の根拠がクイズだけなので"
                    + CONCEPT_MIN_QUIZZES + "問以上にしてください）");
        }
    }

    private PreflightSpec parsePreflight(Map<String, Object> raw, String lessonId) {
        if (raw.containsKey("task") || !MiniJson.list(raw, "extraTasks").isEmpty()
                || !MiniJson.list(raw, "quiz").isEmpty() || !MiniJson.list(raw, "samples").isEmpty()) {
            throw new IllegalStateException("事前確認 " + lessonId + " には問題・クイズ・サンプルを置けません");
        }
        Object value = raw.get("preflight");
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("事前確認 " + lessonId + " のpreflight設定がありません");
        }
        Map<String, Object> spec = MiniJson.asObj(value);
        List<PreflightCheck> checks = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Object entry : MiniJson.list(spec, "checks")) {
            Map<String, Object> check = MiniJson.asObj(entry);
            String id = MiniJson.requireStr(check, "id");
            if (!ids.add(id) || !id.matches("[a-z0-9-]+")) {
                throw new IllegalStateException("事前確認 " + lessonId + " のcheck idが不正です: " + id);
            }
            String type = MiniJson.requireStr(check, "type");
            boolean required = check.get("required") != Boolean.FALSE;
            String tool = MiniJson.str(check, "tool", "");
            String minimumVersion = MiniJson.str(check, "minimumVersion", "");
            int port = MiniJson.intOf(check, "port", 0);
            if (type.equals("tool")) {
                if (!PREFLIGHT_TOOLS.contains(tool) || port != 0) {
                    throw new IllegalStateException("事前確認 " + lessonId + " のtool設定が不正です: " + tool);
                }
                if (!minimumVersion.isEmpty() && !minimumVersion.matches("[0-9]+(?:\\.[0-9]+){0,2}")) {
                    throw new IllegalStateException("事前確認 " + lessonId + " のminimumVersionが不正です");
                }
                // `jcmd` と `jfr` は版を表示しない。`jcmd -h` の使用方法に混ざる数字を版として
                // 読むと必ず不合格になるので、最低版を書けないようにしておく。
                if (!minimumVersion.isEmpty() && (tool.equals("jcmd") || tool.equals("jfr"))) {
                    throw new IllegalStateException("事前確認 " + lessonId + " の " + tool
                            + " にminimumVersionは書けません（版ではなく動くかどうかで判定します）");
                }
            } else if (type.equals("port")) {
                if (!tool.isEmpty() || !minimumVersion.isEmpty() || port < 1024 || port > 65535) {
                    throw new IllegalStateException("事前確認 " + lessonId + " のport設定が不正です: " + port);
                }
            } else {
                throw new IllegalStateException("事前確認 " + lessonId + " のcheck typeはtool / portにしてください");
            }
            checks.add(new PreflightCheck(
                    id, type, MiniJson.requireStr(check, "label"), required, tool,
                    minimumVersion, port, MiniJson.requireStr(check, "help")));
        }
        if (checks.isEmpty() || checks.size() > 10) {
            throw new IllegalStateException("事前確認 " + lessonId + " のcheck数が不正です: " + checks.size());
        }
        return new PreflightSpec(
                MiniJson.str(spec, "buttonLabel", "環境を確認する"), List.copyOf(checks));
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
    private List<Task> parseTasks(Map<String, Object> raw, String lessonId,
                                 java.util.Set<String> objectiveIds) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(parseTask(raw, lessonId, 1, "practice", objectiveIds));
        for (Object o : MiniJson.list(raw, "extraTasks")) {
            tasks.add(parseTask(MiniJson.asObj(o), lessonId, tasks.size() + 1, "drill", objectiveIds));
        }
        return List.copyOf(tasks);
    }

    private Task parseTask(Map<String, Object> raw, String lessonId, int number, String defaultKind,
                           java.util.Set<String> objectiveIds) {
        String id = String.valueOf(number);
        String where = "レッスン " + lessonId + " の問題" + number;

        String type = MiniJson.str(raw, "type", "single-file");
        if (!type.equals("single-file") && !type.equals("artifact") && !type.equals("project")
                && !type.equals("runtime-lab")) {
            throw new IllegalStateException(where + " の type は single-file / artifact / project / runtime-lab "
                    + "のいずれかにしてください: " + type);
        }

        List<TestCase> cases = new ArrayList<>();
        collectCases(cases, MiniJson.list(raw, "visibleCases"), false);
        collectCases(cases, MiniJson.list(raw, "hiddenCases"), true);
        if (type.equals("single-file") && cases.isEmpty()) {
            throw new IllegalStateException(where + " にテストケースがありません");
        }
        if (!type.equals("single-file") && !cases.isEmpty()) {
            throw new IllegalStateException(where + " の " + type + " 問題には "
                    + "visibleCases / hiddenCases を使えません");
        }

        List<String> hints = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "hints")) {
            if (o instanceof String s) {
                hints.add(s);
            }
        }

        List<String> rubric = new ArrayList<>();
        for (String dimension : stringList(raw, "rubric")) {
            if (!Task.RUBRIC_DIMENSIONS.contains(dimension)) {
                throw new IllegalStateException(where + " の rubric は "
                        + String.join(" / ", Task.RUBRIC_DIMENSIONS)
                        + " のいずれかにしてください: " + dimension);
            }
            if (rubric.contains(dimension)) {
                throw new IllegalStateException(where + " の rubric が重複しています: " + dimension);
            }
            rubric.add(dimension);
        }

        List<SourceCheck> sourceChecks = new ArrayList<>();
        for (Object o : MiniJson.list(raw, "sourceChecks")) {
            Map<String, Object> check = MiniJson.asObj(o);
            sourceChecks.add(SourceCheck.of(
                    MiniJson.requireStr(check, "pattern"),
                    MiniJson.intOf(check, "minimum", 1),
                    MiniJson.intOf(check, "maximum", -1),
                    MiniJson.requireStr(check, "message")));
        }
        if (!type.equals("single-file") && !sourceChecks.isEmpty()) {
            throw new IllegalStateException(where + " の " + type + " 問題には sourceChecks を使えません");
        }

        ArtifactSpec artifact = type.equals("artifact") ? parseArtifact(raw, where) : null;
        ProjectSpec project = type.equals("project") ? parseProject(raw, where) : null;
        RuntimeLabSpec runtimeLab = type.equals("runtime-lab") ? parseRuntimeLab(raw, where) : null;

        String kind = MiniJson.str(raw, "kind", defaultKind);
        if (!kind.equals("practice") && !kind.equals("drill") && !kind.equals("applied")) {
            throw new IllegalStateException(where + " の kind は practice / drill / applied "
                    + "のいずれかにしてください: " + kind);
        }
        boolean required = raw.get("required") != Boolean.FALSE;
        if (!required && !kind.equals("applied")) {
            throw new IllegalStateException(where + " の任意問題はkindをappliedにしてください");
        }

        return new Task(
                id,
                kind,
                required,
                type,
                MiniJson.str(raw, "task", ""),
                type.equals("artifact") ? MiniJson.requireStr(raw, "starterContent")
                        : (type.equals("project") || type.equals("runtime-lab")
                        ? "" : MiniJson.str(raw, "starterCode", defaultStarter())),
                List.copyOf(cases),
                List.copyOf(hints),
                MiniJson.str(raw, "solution", ""),
                List.copyOf(sourceChecks),
                artifact,
                project,
                runtimeLab,
                List.copyOf(rubric),
                List.copyOf(parseObjectiveIds(raw, where, objectiveIds)));
    }

    private RuntimeLabSpec parseRuntimeLab(Map<String, Object> raw, String where) {
        Object value = raw.get("runtimeLab");
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(where + " の runtimeLab 設定がありません");
        }
        Map<String, Object> spec = MiniJson.asObj(value);

        // ファイル隔離、solution非公開、固定script検証はprojectと同じ境界を再利用する。
        Map<String, Object> projectRaw = new LinkedHashMap<>(raw);
        projectRaw.put("project", spec);
        ProjectSpec workspace = parseProject(projectRaw, where + " runtime-lab");
        if (!workspace.command().get(0).startsWith("./")) {
            throw new IllegalStateException(where
                    + " の runtime-lab.command は終了処理を持つ ./配下の固定scriptにしてください");
        }

        List<String> capabilities = stringList(spec, "capabilities");
        if (capabilities.isEmpty() || capabilities.size() > RUNTIME_CAPABILITIES.size()
                || capabilities.stream().anyMatch(capability -> !RUNTIME_CAPABILITIES.contains(capability))
                || new java.util.HashSet<>(capabilities).size() != capabilities.size()) {
            throw new IllegalStateException(where + " の runtimeLab.capabilities が不正です");
        }

        List<String> requiredTools = stringList(spec, "requiredTools");
        if (requiredTools.isEmpty() || requiredTools.size() > RUNTIME_TOOLS.size()
                || requiredTools.stream().anyMatch(tool -> !RUNTIME_TOOLS.contains(tool))
                || new java.util.HashSet<>(requiredTools).size() != requiredTools.size()) {
            throw new IllegalStateException(where + " の runtimeLab.requiredTools が不正です");
        }

        List<String> requiredImages = stringList(spec, "requiredImages");
        if (!requiredTools.contains("docker") && !requiredTools.contains("docker-or-podman")
                && !requiredImages.isEmpty()) {
            throw new IllegalStateException(where
                    + " の requiredImages にはdockerまたはdocker-or-podmanが必要です");
        }
        if (requiredImages.size() > 5 || requiredImages.stream().anyMatch(image ->
                image.length() > 200 || !RUNTIME_IMAGE.matcher(image).matches())) {
            throw new IllegalStateException(where + " の runtimeLab.requiredImages が不正です");
        }

        List<RuntimeCheck> checks = new ArrayList<>();
        java.util.Set<String> checkIds = new java.util.HashSet<>();
        for (Object entry : MiniJson.list(spec, "checks")) {
            Map<String, Object> check = MiniJson.asObj(entry);
            String id = MiniJson.requireStr(check, "id");
            if (!id.matches("[a-z0-9-]+") || !checkIds.add(id)) {
                throw new IllegalStateException(where + " のruntime check idが不正です: " + id);
            }
            checks.add(new RuntimeCheck(id, MiniJson.requireStr(check, "label")));
        }
        if (checks.isEmpty() || checks.size() > 20) {
            throw new IllegalStateException(where + " の runtimeLab.checks 数が不正です: " + checks.size());
        }
        return new RuntimeLabSpec(workspace, List.copyOf(capabilities), List.copyOf(requiredTools),
                List.copyOf(requiredImages), List.copyOf(checks));
    }

    private ProjectSpec parseProject(Map<String, Object> raw, String where) {
        Object value = raw.get("project");
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(where + " の project 設定がありません");
        }
        Map<String, Object> spec = MiniJson.asObj(value);
        String source = MiniJson.requireStr(spec, "source");
        if (!source.startsWith("labs/") || !safeRelativePath(source)) {
            throw new IllegalStateException(where + " の project.source は labs/ 以下の安全な相対パスにしてください");
        }

        Path sourceDir;
        Path labsDir;
        try {
            Path repository = contentDir.toRealPath().getParent();
            labsDir = repository.resolve("labs").toRealPath();
            sourceDir = repository.resolve(source).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(where + " のproject sourceを読めません: " + source, e);
        }
        if (!sourceDir.startsWith(labsDir) || !Files.isDirectory(sourceDir)) {
            throw new IllegalStateException(where + " の project.source がlabs外またはディレクトリではありません: " + source);
        }

        List<String> excluded = new ArrayList<>(PROJECT_GENERATED_DIRS);
        for (String entry : stringList(spec, "exclude")) {
            if (!safeRelativePath(entry)) {
                throw new IllegalStateException(where + " の project.exclude に使えないパスがあります: " + entry);
            }
            excluded.add(entry);
        }

        Map<String, String> solutionByPath = new LinkedHashMap<>();
        for (Object entry : MiniJson.list(spec, "editableFiles")) {
            Map<String, Object> file = MiniJson.asObj(entry);
            String path = MiniJson.requireStr(file, "path");
            String solutionPath = MiniJson.requireStr(file, "solutionPath");
            validateProjectFile(sourceDir, path, where);
            validateProjectFile(sourceDir, solutionPath, where);
            if (path.equals(solutionPath)) {
                throw new IllegalStateException(where + " の編集対象と模範解答は別ファイルにしてください: " + path);
            }
            if (solutionByPath.put(path, solutionPath) != null) {
                throw new IllegalStateException(where + " のeditableFilesが重複しています: " + path);
            }
        }
        if (solutionByPath.isEmpty()) {
            throw new IllegalStateException(where + " の project.editableFiles が空です");
        }

        List<ProjectFile> files = discoverProjectFiles(sourceDir, excluded, solutionByPath, where);
        for (String editable : solutionByPath.keySet()) {
            if (files.stream().noneMatch(file -> file.path().equals(editable))) {
                throw new IllegalStateException(where + " の編集対象が表示対象から除外されています: " + editable);
            }
        }

        List<String> command = stringList(spec, "command");
        if (command.isEmpty()) {
            throw new IllegalStateException(where + " の project.command が空です");
        }
        validateProjectCommand(sourceDir, command, solutionByPath.keySet(), where);
        int timeoutSeconds = MiniJson.intOf(spec, "timeoutSeconds", 30);
        int maximumTimeout = raw.get("required") == Boolean.FALSE ? 600 : 60;
        if (timeoutSeconds < 1 || timeoutSeconds > maximumTimeout) {
            throw new IllegalStateException(where + " の timeoutSeconds は1〜"
                    + maximumTimeout + "にしてください");
        }
        return new ProjectSpec(
                MiniJson.str(spec, "name", sourceDir.getFileName().toString()),
                sourceDir,
                List.copyOf(excluded),
                List.copyOf(files),
                List.copyOf(command),
                timeoutSeconds,
                MiniJson.requireStr(spec, "verification"));
    }

    private List<ProjectFile> discoverProjectFiles(Path sourceDir, List<String> excluded,
                                                    Map<String, String> solutionByPath, String where) {
        List<ProjectFile> files = new ArrayList<>();
        java.util.Set<String> solutionPaths = new java.util.HashSet<>(solutionByPath.values());
        int totalCharacters = 0;
        int editableCharacters = 0;
        int solutionCharacters = 0;
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> !Files.isSymbolicLink(candidate))
                    .sorted().toList()) {
                String relative = sourceDir.relativize(path).toString().replace('\\', '/');
                // solutionPathはexcludeの記述忘れがあってもブラウザへ公開しない。
                if (solutionPaths.contains(relative)
                        || isProjectExcluded(relative, excluded) || !isProjectTextFile(path)) continue;
                String solutionPath = solutionByPath.get(relative);
                String starterContent = read(path);
                if (starterContent.length() > ProjectRunnerLimits.FILE_LIMIT_CHARS) {
                    throw new IllegalStateException(where + " のproject fileが長すぎます: " + relative);
                }
                totalCharacters += starterContent.length();
                if (totalCharacters > ProjectRunnerLimits.TOTAL_VISIBLE_LIMIT_CHARS) {
                    throw new IllegalStateException(where + " の表示対象project filesが長すぎます");
                }
                String solutionContent = solutionPath == null ? null : read(sourceDir.resolve(solutionPath));
                if (solutionPath != null) {
                    if (solutionContent.length() > ProjectRunnerLimits.FILE_LIMIT_CHARS) {
                        throw new IllegalStateException(where + " のproject solutionが長すぎます: " + solutionPath);
                    }
                    editableCharacters += starterContent.length();
                    solutionCharacters += solutionContent.length();
                    if (editableCharacters > ProjectRunnerLimits.TOTAL_SUBMISSION_LIMIT_CHARS
                            || solutionCharacters > ProjectRunnerLimits.TOTAL_SUBMISSION_LIMIT_CHARS) {
                        throw new IllegalStateException(where + " の編集対象project filesが長すぎます");
                    }
                }
                files.add(new ProjectFile(
                        relative,
                        projectLanguage(path),
                        starterContent,
                        solutionPath != null,
                        solutionContent));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(where + " のproject filesを読めません", e);
        }
        if (files.isEmpty() || files.size() > 100) {
            throw new IllegalStateException(where + " の表示対象ファイル数が不正です: " + files.size());
        }
        return files;
    }

    /** runnerと同じ制限値をcontent packageから独立して適用する。 */
    private static final class ProjectRunnerLimits {
        private static final int FILE_LIMIT_CHARS = 80_000;
        private static final int TOTAL_SUBMISSION_LIMIT_CHARS = 300_000;
        private static final int TOTAL_VISIBLE_LIMIT_CHARS = 500_000;
    }

    private static void validateProjectCommand(Path sourceDir, List<String> command,
                                               java.util.Set<String> editable, String where) {
        String executable = command.get(0);
        if (executable.equals("mvn") || executable.equals("gradle")) return;
        if (!executable.startsWith("./") || !safeRelativePath(executable.substring(2))) {
            throw new IllegalStateException(where + " のcommandは mvn / gradle / ./配下の固定scriptだけ使えます");
        }
        String script = executable.substring(2);
        validateProjectFile(sourceDir, script, where);
        if (editable.contains(script)) {
            throw new IllegalStateException(where + " の検証scriptを編集対象にはできません: " + script);
        }
    }

    private static void validateProjectFile(Path sourceDir, String relative, String where) {
        if (!safeRelativePath(relative)) {
            throw new IllegalStateException(where + " のproject file pathが不正です: " + relative);
        }
        Path resolved = sourceDir.resolve(relative).normalize();
        if (!resolved.startsWith(sourceDir) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(where + " のproject fileがありません: " + relative);
        }
    }

    private static boolean safeRelativePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")
                || !SAFE_PROJECT_PATH.matcher(path).matches()) return false;
        return !List.of(path.split("/", -1)).contains("..") && !path.contains("//");
    }

    private static boolean isProjectExcluded(String relative, List<String> excluded) {
        for (String entry : excluded) {
            if (relative.equals(entry) || relative.startsWith(entry + "/")
                    || relative.contains("/" + entry + "/")) return true;
        }
        return false;
    }

    private static boolean isProjectTextFile(Path path) {
        String name = path.getFileName().toString();
        if (PROJECT_TEXT_NAMES.contains(name)) return true;
        return PROJECT_TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String projectLanguage(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".xml") || name.equals("pom.xml")) return "xml";
        if (name.endsWith(".properties")) return "properties";
        if (name.endsWith(".sql")) return "sql";
        if (name.endsWith(".json")) return "json";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "yaml";
        if (name.equals("Dockerfile")) return "dockerfile";
        if (name.endsWith(".md")) return "markdown";
        if (name.endsWith(".sh")) return "shell";
        return "text";
    }

    private ArtifactSpec parseArtifact(Map<String, Object> raw, String where) {
        Object value = raw.get("artifact");
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(where + " の artifact 設定がありません");
        }
        Map<String, Object> spec = MiniJson.asObj(value);
        String path = MiniJson.requireStr(spec, "path");
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || List.of(path.split("/", -1)).contains("..")) {
            throw new IllegalStateException(where + " の artifact.path は安全な相対パスにしてください: " + path);
        }

        String format = MiniJson.requireStr(spec, "format");
        if (!List.of("xml", "json", "properties", "text", "sql", "dockerfile", "yaml")
                .contains(format)) {
            throw new IllegalStateException(where + " の artifact.format は xml / json / properties / "
                    + "text / sql / dockerfile / yaml のいずれかにしてください: " + format);
        }

        List<ArtifactCheck> checks = new ArrayList<>();
        for (Object entry : MiniJson.list(spec, "checks")) {
            Map<String, Object> check = MiniJson.asObj(entry);
            String checkType = MiniJson.requireStr(check, "type");
            if (!List.of("xpath", "regex", "property", "jsonPointer").contains(checkType)) {
                throw new IllegalStateException(where + " の artifact.checks[].type が不正です: " + checkType);
            }
            if (checkType.equals("xpath") && !format.equals("xml")) {
                throw new IllegalStateException(where + " の xpath 検査は XML だけで使えます");
            }
            if (checkType.equals("property") && !format.equals("properties")) {
                throw new IllegalStateException(where + " の property 検査は properties だけで使えます");
            }
            if (checkType.equals("jsonPointer") && !format.equals("json")) {
                throw new IllegalStateException(where + " の jsonPointer 検査は JSON だけで使えます");
            }
            Object expected = check.get("expected");
            if ((checkType.equals("property") || checkType.equals("jsonPointer"))
                    && !check.containsKey("expected")) {
                throw new IllegalStateException(where + " の " + checkType + " 検査には expected が必要です");
            }
            checks.add(new ArtifactCheck(
                    checkType,
                    MiniJson.requireStr(check, "expression"),
                    expected,
                    MiniJson.requireStr(check, "message")));
        }
        if (checks.isEmpty()) {
            throw new IllegalStateException(where + " の artifact.checks が空です");
        }
        return new ArtifactSpec(path, format, List.copyOf(checks));
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
