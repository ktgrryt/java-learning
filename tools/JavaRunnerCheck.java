import jq.runner.Diagnostic;
import jq.runner.JavaRunner;
import jq.runner.RunResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** JavaRunnerの入出力、コンパイルエラーのヒント、正常終了・timeout後の子プロセス回収を検査する。 */
public final class JavaRunnerCheck {

    private JavaRunnerCheck() {
    }

    public static void main(String[] args) throws Exception {
        standardInputStillWorks();
        wrapperAddsNothingToStderr();
        importHintNamesThePackage();
        stackTraceKeepsTheLearnersLine();
        normalExitCleansUpChild();
        timeoutCleansUpChild();
        System.out.println("java runner（入出力・import忘れのヒント・自分の行が残るstack trace・子プロセス回収）"
                + ": すべて合格");
    }

    /**
     * import を書き忘れたときのヒントが、書き足す1行をそのまま示すことを確かめる。
     *
     * <p>教材は「その道具を初めて使う問題」ではひな形に import を書かない
     * （`docs/guide.md`「道具の import は、初めて使う問題では書かない」）。忘れたときに
     * 最初に返るのがこのヒントなので、ここが汎用の「つづりを確かめましょう」に戻ると、
     * 学習者は import が足りないことに気づけない。
     */
    private static void importHintNamesThePackage() throws Exception {
        requireHint("""
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println(sc.nextInt());
                    }
                }
                """, "import java.util.Scanner;", "Scanner の import 忘れ");

        // 静的メソッド呼び出しの import 忘れは、javacから「シンボル: 変数 Files」として届く。
        // メッセージの種別（クラス／変数）で判定すると、この形を取りこぼす。
        requireHint("""
                import java.nio.file.Path;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of("memo.txt"), "x");
                    }
                }
                """, "import java.nio.file.Files;", "Files の import 忘れ（変数として報告される形）");

        // 自作クラスの書き忘れに import を勧めてはいけない（JDKに同名のクラスが無い）
        requireNoHint("""
                public class Main {
                    public static void main(String[] args) {
                        Dog dog = new Dog();
                        System.out.println(dog);
                    }
                }
                """, "import", "自作クラスの書き忘れ");
    }

    private static void requireHint(String source, String expected, String what) {
        List<String> hints = compileErrorHints(source);
        require(hints.stream().anyMatch(hint -> hint.contains(expected)),
                what + "のヒントが `" + expected + "` を案内していません: " + hints);
    }

    private static void requireNoHint(String source, String unexpected, String what) {
        List<String> hints = compileErrorHints(source);
        require(hints.stream().noneMatch(hint -> hint.contains(unexpected)),
                what + "のヒントに `" + unexpected + "` が混ざっています: " + hints);
    }

    /** わざとコンパイルに失敗させて、診断に付いたヒントだけを取り出す。 */
    private static List<String> compileErrorHints(String source) {
        JavaRunner runner = new JavaRunner();
        try (JavaRunner.Compiled compiled = runner.compile(source)) {
            require(!compiled.success(), "コンパイルが失敗する想定のコードが通りました");
            return compiled.diagnostics().stream().map(Diagnostic::hint).toList();
        }
    }

    /**
     * 実行wrapperが自分の出力をstderrへ混ぜないことを確かめる。
     *
     * <p>プロセスグループを作るための {@code set -m} を有効なままにすると、bashが
     * {@code [1]+ Done "$@"} をstderrへ出す。正常なコードが実行時エラーに見え、
     * 検証も全サンプルを失敗と判定するので、正常終了・stderr出力・異常終了の
     * どれでもwrapper由来の行が混ざらないことを固定する。
     */
    private static void wrapperAddsNothingToStderr() throws Exception {
        assertStderr("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("ok");
                    }
                }
                """, "", 0, "正常終了したコードのstderrにwrapperの出力が混ざっています");

        // 学習者のstderrはそのまま届く必要がある（wrapperが捨ててはいけない）
        assertStderr("""
                public class Main {
                    public static void main(String[] args) {
                        System.err.println("learner stderr");
                    }
                }
                """, "learner stderr", 0, "コードが書いたstderrが届いていません");

        assertStderr("""
                public class Main {
                    public static void main(String[] args) {
                        System.exit(3);
                    }
                }
                """, "", 3, "異常終了したコードのstderrにwrapperの出力が混ざっています");
    }

    /**
     * JDKの中で投げられた例外でも、stack traceに<b>学習者自身の行</b>が残ることを確かめる。
     *
     * <p>{@code at} 行は3本までに刈り込んでいる。上から順に3本残す作りだと、
     * {@code Integer.parseInt("abc")} のように {@code java.base} の行が先に並ぶ例外で
     * {@code at Main.main(Main.java:N)} が押し出され、<b>いちばん知りたい行が画面から消える</b>。
     * 第5章（{@code ch64}）は「最初に出てくる自分のファイルの行を探す」ことを教えているので、
     * ここが戻ると教材の言うとおりに読めなくなる（2026-08-19に「試しに実行」で発覚）。
     */
    private static void stackTraceKeepsTheLearnersLine() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(Integer.parseInt("abc"));
                    }
                }
                """;
        JavaRunner runner = new JavaRunner();
        try (JavaRunner.Compiled compiled = runner.compile(source)) {
            require(compiled.success(), "stack trace検査用コードをコンパイルできませんでした: "
                    + compiled.diagnostics());
            RunResult result = runner.run(compiled, "");
            String stderr = result.stderr();
            require(stderr.contains("NumberFormatException"),
                    "例外の名前が出ていません: " + stderr);
            require(stderr.contains("at Main.main(Main.java:"),
                    "学習者自身の行（at Main.main）が刈り込まれています: " + stderr);
        }
    }

    private static void assertStderr(String source, String expectedStderr,
                                     int expectedExitCode, String message) throws Exception {
        JavaRunner runner = new JavaRunner();
        try (JavaRunner.Compiled compiled = runner.compile(source)) {
            require(compiled.success(), "stderr検査用コードをコンパイルできませんでした: "
                    + compiled.diagnostics());
            RunResult result = runner.run(compiled, "");
            require(!result.timedOut(), "stderr検査用コードがtimeoutしました: " + result);
            require(result.exitCode() == expectedExitCode,
                    "終了コードが " + expectedExitCode + " ではありません: " + result);
            require(result.stderr().strip().equals(expectedStderr),
                    message + ": " + result.stderr());
        }
    }

    private static void standardInputStillWorks() throws Exception {
        String source = """
                import java.util.Scanner;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(new Scanner(System.in).nextLine());
                    }
                }
                """;
        JavaRunner runner = new JavaRunner();
        try (JavaRunner.Compiled compiled = runner.compile(source)) {
            require(compiled.success(), "標準入力検査用コードをコンパイルできませんでした: "
                    + compiled.diagnostics());
            RunResult result = runner.run(compiled, "stdin through wrapper");
            require(!result.timedOut() && result.exitCode() == 0
                            && result.stdout().contains("stdin through wrapper"),
                    "プロセスグループ経由で標準入力を渡せませんでした: " + result);
        }
    }

    private static void normalExitCleansUpChild() throws Exception {
        Path marker = Files.createTempFile("jq-java-child-", ".marker");
        Files.delete(marker);
        String markerLiteral = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String source = """
                import java.nio.file.Path;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                        Process child = new ProcessBuilder(
                                java, "-cp", System.getProperty("java.class.path"),
                                "Sleeper", "%s").start();
                    }
                }

                class Sleeper {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(1_000);
                        java.nio.file.Files.writeString(Path.of(args[0]), "child survived");
                    }
                }
                """.formatted(markerLiteral);

        try {
            JavaRunner runner = new JavaRunner();
            try (JavaRunner.Compiled compiled = runner.compile(source)) {
                require(compiled.success(), "子プロセス検査用コードをコンパイルできませんでした: "
                        + compiled.diagnostics());
                RunResult result = runner.run(compiled, "");
                require(!result.timedOut() && result.exitCode() == 0,
                        "親プロセスが正常終了しませんでした: " + result);
            }
            Thread.sleep(1_500);
            require(!Files.exists(marker),
                    "親が正常終了したあとも子Javaプロセスが動作を続けています");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    private static void timeoutCleansUpChild() throws Exception {
        Path marker = Files.createTempFile("jq-java-timeout-child-", ".marker");
        Files.delete(marker);
        String markerLiteral = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String source = """
                import java.nio.file.Path;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                        new ProcessBuilder(
                                java, "-cp", System.getProperty("java.class.path"),
                                "DelayedWriter", "%s").start();
                        Thread.sleep(10_000);
                    }
                }

                class DelayedWriter {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(5_500);
                        java.nio.file.Files.writeString(Path.of(args[0]), "child survived timeout");
                    }
                }
                """.formatted(markerLiteral);

        try {
            JavaRunner runner = new JavaRunner();
            try (JavaRunner.Compiled compiled = runner.compile(source)) {
                require(compiled.success(), "timeout検査用コードをコンパイルできませんでした: "
                        + compiled.diagnostics());
                RunResult result = runner.run(compiled, "");
                require(result.timedOut(), "親Javaプロセスがtimeoutしませんでした: " + result);
            }
            Thread.sleep(1_000);
            require(!Files.exists(marker),
                    "timeout後も子Javaプロセスが動作を続けています");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
