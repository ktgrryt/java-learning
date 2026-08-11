package jq.runner;

import jq.content.SourceFile;

import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ユーザーが書いたJavaコードを一時ディレクトリでコンパイルし、別JVMで実行する。
 *
 * コンパイルは JDK 内蔵の {@link JavaCompiler} をその場で呼ぶので速い。
 * 実行は必ず別プロセスにする。無限ループ・{@code System.exit}・大量出力から
 * このサーバ自身を守るためで、時間とメモリと出力量に上限をかけている。
 *
 * 1回コンパイルして標準入力を変えて何度も実行できる（{@link Compiled}）。
 */
public final class JavaRunner {

    /** 1ケースあたりの実行時間の上限。 */
    private static final long TIMEOUT_MS = 5_000;
    /** stdout / stderr それぞれの取り込み上限（文字数ではなくバイト数）。 */
    private static final int OUTPUT_LIMIT_BYTES = 20_000;
    /** 受け付けるソースコードの上限。保存の上限としても使う（{@code jq.web.ApiHandler}）。 */
    public static final int SOURCE_LIMIT_CHARS = 60_000;

    /** class 宣言（修飾子つき）を拾う。 */
    private static final Pattern CLASS_DECL =
            Pattern.compile("\\b(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|abstract\\s+"
                    + "|sealed\\s+|non-sealed\\s+|strictfp\\s+)*(?:class|record|enum|interface)\\s+(\\w+)");
    private static final Pattern MAIN_METHOD =
            Pattern.compile("\\bstatic\\s+(?:public\\s+)?void\\s+main\\s*\\(");

    /**
     * コンパイル済みのコード。使い終わったら必ず {@link #close()} する（try-with-resources 推奨）。
     */
    public static final class Compiled implements AutoCloseable {
        private final Path workDir;
        private final String mainClass;
        private final List<Diagnostic> diagnostics;
        private final boolean success;
        /** 同梱ライブラリのファイル名。スタックトレースから枠組みの行を落とすために使う。 */
        private final Set<String> libFileNames;

        private Compiled(Path workDir, String mainClass, List<Diagnostic> diagnostics, boolean success,
                         Set<String> libFileNames) {
            this.workDir = workDir;
            this.mainClass = mainClass;
            this.diagnostics = diagnostics;
            this.success = success;
            this.libFileNames = libFileNames;
        }

        public boolean success() {
            return success;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public String mainClass() {
            return mainClass;
        }

        @Override
        public void close() {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /**
     * コードをコンパイルする。失敗しても例外は投げず、{@code success()==false} の
     * {@link Compiled} を返す（診断メッセージは {@code diagnostics()} に入る）。
     */
    public Compiled compile(String code) {
        return compile(code, List.of());
    }

    /**
     * 同梱ライブラリと一緒にコンパイルする。
     *
     * Jakarta EE の章のように、JDKに無いクラス（{@code jakarta.servlet.*} など）を
     * 学習者に書かせたい場合に使う。{@code support} のソースは学習者のコードと同じ作業
     * ディレクトリへ、{@link SourceFile#path()} の相対パスのまま書き出してから
     * まとめてコンパイルするので、学習者は本物と同じ import 文を書ける。
     *
     * @param support 一緒にコンパイルするソース。空なら {@link #compile(String)} と同じ
     */
    public Compiled compile(String code, List<SourceFile> support) {
        if (code == null || code.isBlank()) {
            return failed("コードが空です。まずは何か書いてみましょう。");
        }
        if (code.length() > SOURCE_LIMIT_CHARS) {
            return failed("コードが長すぎます（上限 " + SOURCE_LIMIT_CHARS + " 文字）。");
        }

        String className = detectMainClassName(code);
        if (className == null) {
            return failed("class の宣言が見つかりません。"
                    + "`public class Main { public static void main(String[] args) { ... } }` の形になっているか確かめましょう。");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return failed("Javaコンパイラが見つかりません。JRE ではなく JDK で起動してください。");
        }

        String userFileName = className + ".java";
        // 同梱ライブラリと同じファイル名だと、学習者のコードが上書きされて消える。
        // 原因の分かりにくい不具合になるので、その場で名前を変えてもらう。
        for (SourceFile sf : support) {
            if (sf.path().equals(userFileName)) {
                return failed("クラス名 " + className + " は同梱ライブラリのファイルと重なっています。"
                        + "別のクラス名にしてください。");
            }
        }

        Path workDir;
        List<Path> units = new ArrayList<>(support.size() + 1);
        Set<String> libFileNames = new LinkedHashSet<>();
        try {
            workDir = Files.createTempDirectory("jq-run-");
            Path userFile = workDir.resolve(userFileName);
            Files.writeString(userFile, code, StandardCharsets.UTF_8);
            units.add(userFile);

            for (SourceFile sf : support) {
                Path dest = workDir.resolve(sf.path());
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, sf.content(), StandardCharsets.UTF_8);
                units.add(dest);
                libFileNames.add(dest.getFileName().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("一時ディレクトリを作れません", e);
        }
        // 学習者のファイルと同じ名前のライブラリがあっても、学習者の行は消さない
        libFileNames.remove(userFileName);

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.JAPAN, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(workDir));
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjects(units.toArray(new Path[0]));
            ok = compiler.getTask(
                            null,
                            fileManager,
                            collector,
                            List.of("-encoding", "UTF-8", "-nowarn", "-proc:none"),
                            null,
                            compilationUnits)
                    .call();
        } catch (IOException e) {
            deleteRecursively(workDir);
            throw new UncheckedIOException("コンパイルに失敗しました", e);
        } catch (RuntimeException e) {
            deleteRecursively(workDir);
            return failed("コンパイル中に想定外のエラーが起きました: " + e.getMessage());
        }

        List<Diagnostic> diagnostics = translate(collector, libFileNames);
        if (!ok) {
            deleteRecursively(workDir);
            if (diagnostics.isEmpty()) {
                return failed("コンパイルできませんでした。");
            }
            return new Compiled(null, className, diagnostics, false, Set.copyOf(libFileNames));
        }
        return new Compiled(workDir, className, diagnostics, true, Set.copyOf(libFileNames));
    }

    /**
     * コンパイル済みコードを1回実行する。
     *
     * @param stdin 標準入力へ流す文字列（不要なら空文字）
     */
    public RunResult run(Compiled compiled, String stdin) {
        if (!compiled.success()) {
            throw new IllegalStateException("コンパイルできていないコードは実行できません");
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-XX:TieredStopAtLevel=1",   // 起動を速くする（学習用の小さなコードにJIT最適化は不要）
                "-Xmx256m",                  // 暴走したコードがマシンのメモリを食い尽くさないように
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                // 既定ロケールを固定する。文字コードと同じ理由で、採点を環境に依存させないため。
                // printf の `%.1f` や `%,d`、Scanner の nextDouble、引数なしの toUpperCase は
                // 既定ロケールで結果が変わる（例: de_DE では `20.0` が `20,0` になる）。
                // OSの地域設定やLANGでロケールが決まるので、固定しないと同じ解答が環境によって落ちる。
                "-Duser.language=ja",
                "-Duser.country=JP",
                "-cp", compiled.workDir.toString(),
                compiled.mainClass());
        pb.directory(compiled.workDir.toFile());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("実行プロセスを起動できません", e);
        }

        StreamPump out = new StreamPump(process.getInputStream());
        StreamPump err = new StreamPump(process.getErrorStream());
        out.start();
        err.start();

        // 標準入力を書き込む。読まずに終わるコードもあるので IOException は無視する。
        Thread feeder = new Thread(() -> {
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) {
                    String payload = stdin.endsWith("\n") ? stdin : stdin + "\n";
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {
                // 相手がもう読んでいない場合。無視してよい
            }
        }, "jq-stdin");
        feeder.setDaemon(true);
        feeder.start();

        boolean timedOut = false;
        int exitCode;
        try {
            if (process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                destroyTree(process);
                timedOut = true;
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            timedOut = true;
            exitCode = -1;
        }

        out.finish();
        err.finish();
        return new RunResult(
                out.text(),
                trimStackTrace(err.text(), compiled.libFileNames),
                exitCode,
                timedOut,
                out.truncated() || err.truncated());
    }

    // ------------------------------------------------------------- internals

    /**
     * 実行中のプロセスを、そこから起動された子孫ごと止める。
     *
     * {@code destroyForcibly()} は指定したプロセスだけを止める。学習者のコードが
     * {@code ProcessBuilder} や {@code Runtime#exec} で別のコマンドを起動していると、
     * タイムアウトでJVMだけが消えて、その先のコマンドは残り続ける
     * （「5秒で止めました」と表示されたのにCPUが回り続ける状態になる）。
     *
     * 子孫の一覧は<b>親を止める前に</b>取る。親が消えたあとでは親子関係を辿れないため、
     * 順序が逆だと取り逃がす。
     */
    private static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            descendant.destroyForcibly();
        }
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Compiled failed(String hint) {
        return new Compiled(null, "Main", List.of(new Diagnostic("error", 0, 0, hint, "")), false, Set.of());
    }

    private static List<Diagnostic> translate(DiagnosticCollector<JavaFileObject> collector,
                                              Set<String> libFileNames) {
        List<Diagnostic> list = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() != Kind.ERROR && d.getKind() != Kind.WARNING) {
                continue;
            }
            String message = d.getMessage(Locale.JAPAN);
            String fileName = sourceFileName(d);

            // 同梱ライブラリ側のエラーは、行番号が学習者のコードの行ではない。
            // そのまま「12行目」と出すと自分のコードを疑わせてしまうので、行を指さずに
            // 教材側の問題だと分かる形で見せる（本来は verify-solutions.sh が先に見つける）。
            if (fileName != null && libFileNames.contains(fileName)) {
                list.add(new Diagnostic(
                        "error",
                        0,
                        0,
                        "同梱ライブラリ " + fileName + " でエラー: " + message,
                        "これは教材側の不具合です。あなたのコードは関係ありません。"));
                continue;
            }

            list.add(new Diagnostic(
                    d.getKind() == Kind.ERROR ? "error" : "warning",
                    (int) Math.max(d.getLineNumber(), 0),
                    (int) Math.max(d.getColumnNumber(), 0),
                    message,
                    ErrorTranslator.forCompileError(d.getCode(), message)));
        }
        return List.copyOf(list);
    }

    /** 診断が出たソースのファイル名（パスは落とす）。分からなければ null。 */
    private static String sourceFileName(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        JavaFileObject source = d.getSource();
        if (source == null) {
            return null;
        }
        String name = source.getName();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /**
     * ソースを保存するファイル名（＝ public クラス名、なければ main を持つクラス名）を決める。
     *
     * javac は「public なトップレベル型はファイル名と一致していること」を要求する。
     * そこで public 宣言があればそれを最優先で採る。ファイル内で最初に現れる public 宣言が
     * トップレベルのものなので、先頭から探して最初に見つかったものを使う。
     *
     * public が1つもない場合はファイル名は自由なので、main の直前の宣言を採用する
     * （見つからなければ最初の宣言）。
     */
    static String detectMainClassName(String source) {
        // コメントや文字列の中に書かれた "class Foo" を拾わないよう、先に潰しておく
        String code = blankOutCommentsAndStrings(source);

        List<int[]> declarations = new ArrayList<>(); // {開始位置}
        List<String> names = new ArrayList<>();
        Matcher m = CLASS_DECL.matcher(code);
        while (m.find()) {
            if (m.group().contains("public")) {
                // public な型はファイル名が決まっている。ここで確定できる
                return m.group(1);
            }
            declarations.add(new int[]{m.start()});
            names.add(m.group(1));
        }
        if (names.isEmpty()) {
            return null;
        }
        Matcher mainMatcher = MAIN_METHOD.matcher(code);
        if (mainMatcher.find()) {
            int mainAt = mainMatcher.start();
            int best = -1;
            for (int i = 0; i < declarations.size(); i++) {
                if (declarations.get(i)[0] < mainAt) {
                    best = i;
                }
            }
            if (best >= 0) {
                return names.get(best);
            }
        }
        return names.get(0);
    }

    /**
     * コメントと文字列リテラル（テキストブロックを含む）の中身を空白に置き換える。
     *
     * 文字数と改行位置はそのまま保つので、置き換えたあとの位置は元のソースと一致する。
     * 解説用のコメントやテキストブロックに書いた {@code public class Foo} を
     * 本物の宣言と間違えないようにするための前処理。
     */
    static String blankOutCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < n && code.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(code.charAt(i) == '*' && i + 1 < n && code.charAt(i + 1) == '/')) {
                    out.append(code.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && next == '"' && i + 2 < n && code.charAt(i + 2) == '"') {
                // テキストブロック
                out.append("   ");
                i += 3;
                while (i < n && !isTextBlockEnd(code, i)) {
                    if (code.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(code.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && code.charAt(i) != quote && code.charAt(i) != '\n') {
                    if (code.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n && code.charAt(i) == quote) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isTextBlockEnd(String code, int i) {
        return code.charAt(i) == '"'
                && i + 2 < code.length()
                && code.charAt(i + 1) == '"'
                && code.charAt(i + 2) == '"';
    }

    /**
     * スタックトレースからアプリ内部の行を落とし、初心者に読める分量に刈り込む。
     * 例外の種類とメッセージ、そしてユーザーコードの行だけを残す。
     *
     * @param libFileNames 同梱ライブラリのファイル名。枠組みの中の行も落とす。
     *                     疑似コンテナ経由だと {@code MiniWeb.java} などが間に挟まるので、
     *                     残しておくと学習者が自分の行を見つけにくくなる
     */
    static String trimStackTrace(String stderr, Set<String> libFileNames) {
        if (stderr == null || stderr.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : stderr.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("at ")) {
                // ユーザーのソース（Main.java など）に対応する行だけ残す
                if (trimmed.contains(".java:") && !isLibFrame(trimmed, libFileNames)) {
                    kept.add(line);
                }
                continue;
            }
            kept.add(line);
        }
        // 「at」行は多くても3本まで
        List<String> result = new ArrayList<>();
        int atCount = 0;
        for (String line : kept) {
            if (line.strip().startsWith("at ")) {
                if (++atCount > 3) {
                    continue;
                }
            }
            result.add(line);
        }
        return String.join("\n", result).strip();
    }

    /**
     * その {@code at} 行が同梱ライブラリの中か判定する。
     *
     * 行の形は {@code at MiniWeb.serve(MiniWeb.java:40)} なので、カッコの中のファイル名を見る。
     */
    private static boolean isLibFrame(String frame, Set<String> libFileNames) {
        if (libFileNames.isEmpty()) {
            return false;
        }
        int open = frame.lastIndexOf('(');
        int dot = frame.lastIndexOf(".java:");
        if (open < 0 || dot < open) {
            return false;
        }
        return libFileNames.contains(frame.substring(open + 1, dot + ".java".length()));
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 消せなくても致命的ではない（OSの一時領域なので後で片付く）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 子プロセスの出力を上限つきで吸い出すスレッド。読まないとパイプが詰まって子が止まる。 */
    private static final class StreamPump extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        StreamPump(InputStream in) {
            super("jq-pump");
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    int room = OUTPUT_LIMIT_BYTES - buffer.size();
                    if (room <= 0) {
                        truncated = true;
                        continue; // 読み捨てる（読むのをやめると子プロセスが止まってしまう）
                    }
                    buffer.write(chunk, 0, Math.min(n, room));
                    if (n > room) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // プロセスが強制終了された場合など
            }
        }

        void finish() {
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
