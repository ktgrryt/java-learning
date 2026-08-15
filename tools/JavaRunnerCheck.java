import jq.runner.JavaRunner;
import jq.runner.RunResult;

import java.nio.file.Files;
import java.nio.file.Path;

/** JavaRunnerの入出力と、正常終了・timeout後の子プロセス回収を検査する。 */
public final class JavaRunnerCheck {

    private JavaRunnerCheck() {
    }

    public static void main(String[] args) throws Exception {
        standardInputStillWorks();
        wrapperAddsNothingToStderr();
        normalExitCleansUpChild();
        timeoutCleansUpChild();
        System.out.println("java runner process cleanup: すべて合格");
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
