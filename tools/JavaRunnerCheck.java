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
        normalExitCleansUpChild();
        timeoutCleansUpChild();
        System.out.println("java runner process cleanup: すべて合格");
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
