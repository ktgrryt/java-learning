package jq.runner;

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
import java.util.List;
import java.util.Locale;
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
    /** 受け付けるソースコードの上限。 */
    private static final int SOURCE_LIMIT_CHARS = 60_000;

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

        private Compiled(Path workDir, String mainClass, List<Diagnostic> diagnostics, boolean success) {
            this.workDir = workDir;
            this.mainClass = mainClass;
            this.diagnostics = diagnostics;
            this.success = success;
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

        Path workDir;
        try {
            workDir = Files.createTempDirectory("jq-run-");
            Files.writeString(workDir.resolve(className + ".java"), code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("一時ディレクトリを作れません", e);
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.JAPAN, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(workDir));
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjects(workDir.resolve(className + ".java"));
            ok = compiler.getTask(
                            null,
                            fileManager,
                            collector,
                            List.of("-encoding", "UTF-8", "-nowarn", "-proc:none"),
                            null,
                            units)
                    .call();
        } catch (IOException e) {
            deleteRecursively(workDir);
            throw new UncheckedIOException("コンパイルに失敗しました", e);
        } catch (RuntimeException e) {
            deleteRecursively(workDir);
            return failed("コンパイル中に想定外のエラーが起きました: " + e.getMessage());
        }

        List<Diagnostic> diagnostics = translate(collector);
        if (!ok) {
            deleteRecursively(workDir);
            if (diagnostics.isEmpty()) {
                return failed("コンパイルできませんでした。");
            }
            return new Compiled(null, className, diagnostics, false);
        }
        return new Compiled(workDir, className, diagnostics, true);
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
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                timedOut = true;
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            timedOut = true;
            exitCode = -1;
        }

        out.finish();
        err.finish();
        return new RunResult(
                out.text(),
                trimStackTrace(err.text()),
                exitCode,
                timedOut,
                out.truncated() || err.truncated());
    }

    // ------------------------------------------------------------- internals

    private static Compiled failed(String hint) {
        return new Compiled(null, "Main", List.of(new Diagnostic("error", 0, 0, hint, "")), false);
    }

    private static List<Diagnostic> translate(DiagnosticCollector<JavaFileObject> collector) {
        List<Diagnostic> list = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() != Kind.ERROR && d.getKind() != Kind.WARNING) {
                continue;
            }
            String message = d.getMessage(Locale.JAPAN);
            list.add(new Diagnostic(
                    d.getKind() == Kind.ERROR ? "error" : "warning",
                    (int) Math.max(d.getLineNumber(), 0),
                    (int) Math.max(d.getColumnNumber(), 0),
                    message,
                    ErrorTranslator.forCompileError(d.getCode(), message)));
        }
        return List.copyOf(list);
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
     */
    static String trimStackTrace(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : stderr.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("at ")) {
                // ユーザーのソース（Main.java など）に対応する行だけ残す
                if (trimmed.contains(".java:")) {
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
