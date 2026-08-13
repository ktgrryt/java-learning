import jq.content.ProjectFile;
import jq.content.ProjectSpec;
import jq.content.RuntimeCheck;
import jq.content.RuntimeLabSpec;
import jq.runner.RuntimeLabRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** runtime-labの隔離、動的環境変数、構造化検査、timeout、環境不足を回帰検査する。 */
public final class RuntimeLabRunnerCheck {
    public static void main(String[] args) throws Exception {
        Path source = Files.createTempDirectory("jq-runtime-source-");
        try {
            write(source, "exercise/value.txt", "starter\n");
            write(source, "reference/value.txt", "completed\n");
            write(source, "verify.sh", """
                    set -eu
                    test -n "$JQ_LAB_PORT"
                    test -n "$JQ_LAB_RUN_ID"
                    if test "$(cat exercise/value.txt)" = completed; then
                      printf 'JQ_CHECK\\tPASS\\tprocess\\t実プロセスの検査に成功\\n'
                      printf 'JQ_CHECK\\tPASS\\tcleanup\\t終了処理を確認\\n'
                      exit 0
                    fi
                    printf 'JQ_CHECK\\tFAIL\\tprocess\\t値が未完成\\n'
                    exit 1
                    """);
            write(source, "slow.sh", "sleep 3\n");

            ProjectFile editable = new ProjectFile(
                    "exercise/value.txt", "text", "starter\n", true, "completed\n");
            List<RuntimeCheck> checks = List.of(
                    new RuntimeCheck("process", "process"), new RuntimeCheck("cleanup", "cleanup"));
            RuntimeLabSpec normal = spec(source, editable, List.of("/bin/sh", "verify.sh"), 5, checks);
            RuntimeLabRunner runner = new RuntimeLabRunner();

            RuntimeLabRunner.Result passed = runner.run(normal,
                    Map.of("exercise/value.txt", "completed\n"));
            require(passed.available() && passed.started() && passed.allPass()
                    && passed.checks().size() == 2, "正常なruntime labが通りません: " + passed);
            require(Files.readString(source.resolve("exercise/value.txt")).equals("starter\n"),
                    "提出によって元labが変更されました");

            RuntimeLabRunner.Result failed = runner.run(normal,
                    Map.of("exercise/value.txt", "wrong\n"));
            require(failed.available() && failed.started() && !failed.allPass(),
                    "FAIL markerが不合格になりませんでした: " + failed);
            require(failed.checks().stream().anyMatch(check -> !check.pass()),
                    "未報告checkが不合格になりませんでした");

            RuntimeLabSpec slow = spec(source, editable, List.of("/bin/sh", "slow.sh"), 1, checks);
            RuntimeLabRunner.Result timedOut = runner.run(slow,
                    Map.of("exercise/value.txt", "completed\n"));
            require(timedOut.timedOut() && !timedOut.allPass(), "timeoutを検出できません: " + timedOut);

            RuntimeLabSpec unavailable = new RuntimeLabSpec(normal.workspace(), List.of("container"),
                    List.of("docker"), List.of("jq-runtime-image-that-must-not-exist:never"), checks);
            RuntimeLabRunner.Result missing = runner.run(unavailable,
                    Map.of("exercise/value.txt", "completed\n"));
            require(!missing.available() && !missing.started() && !missing.error().isBlank(),
                    "環境不足を実装不正解と分離できません: " + missing);

            RuntimeLabSpec alternative = new RuntimeLabSpec(normal.workspace(), List.of("container"),
                    List.of("docker-or-podman"),
                    List.of("jq-runtime-image-that-must-not-exist:never"), checks);
            RuntimeLabRunner.Result alternativeMissing = runner.run(alternative,
                    Map.of("exercise/value.txt", "completed\n"));
            require(!alternativeMissing.available() && !alternativeMissing.started()
                            && !alternativeMissing.error().isBlank(),
                    "Docker/Podman代替要件の環境不足を分離できません: " + alternativeMissing);

            System.out.println("runtime lab runner: すべて合格");
        } finally {
            deleteRecursively(source);
        }
    }

    private static RuntimeLabSpec spec(Path source, ProjectFile editable, List<String> command,
                                       int timeout, List<RuntimeCheck> checks) {
        ProjectSpec workspace = new ProjectSpec("runtime-check", source, List.of("reference"),
                List.of(editable), command, timeout, "runtime processを確認する");
        return new RuntimeLabSpec(workspace, List.of("server"), List.of("java"), List.of(), checks);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
