import jq.content.ProjectFile;
import jq.content.ProjectSpec;
import jq.runner.ProjectRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** project runnerの隔離、複数ファイル差し替え、失敗、timeoutを回帰検査する。 */
public final class ProjectRunnerCheck {

    private ProjectRunnerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Files.createTempDirectory("jq-project-source-");
        try {
            write(source, "app/Main.txt", "starter\n");
            write(source, "reference/Main.txt", "completed\n");
            write(source, "verify.sh", """
                    set -eu
                    test ! -e reference/Main.txt
                    test "$(cat app/Main.txt)" = completed
                    printf 'project verification passed\\n'
                    """);
            write(source, "slow.sh", "sleep 3\n");

            ProjectFile editable = new ProjectFile(
                    "app/Main.txt", "text", "starter\n", true, "completed\n");
            ProjectSpec normal = new ProjectSpec(
                    "runner-check", source, List.of("reference"), List.of(editable),
                    List.of("/bin/sh", "verify.sh"), 5, "Main.txtが完成していること");

            ProjectRunner runner = new ProjectRunner();
            ProjectRunner.Result passed = runner.run(normal, Map.of("app/Main.txt", "completed\n"));
            require(passed.started() && passed.allPass() && passed.exitCode() == 0,
                    "正しい提出が通りませんでした: " + passed);
            require(passed.output().contains("project verification passed"),
                    "検証出力を取得できませんでした");

            ProjectRunner.Result failed = runner.run(normal, Map.of("app/Main.txt", "wrong\n"));
            require(failed.started() && !failed.allPass() && failed.exitCode() != 0,
                    "誤った提出が不合格になりませんでした: " + failed);
            require(Files.readString(source.resolve("app/Main.txt")).equals("starter\n"),
                    "提出によって元labが変更されました");

            try {
                runner.run(normal, Map.of("unknown.txt", "completed\n"));
                throw new AssertionError("許可されていないファイルが拒否されませんでした");
            } catch (IllegalArgumentException expected) {
                // expected
            }

            ProjectSpec slow = new ProjectSpec(
                    "timeout-check", source, List.of("reference"), List.of(editable),
                    List.of("/bin/sh", "slow.sh"), 1, "時間内に終わること");
            ProjectRunner.Result timedOut = runner.run(slow, Map.of("app/Main.txt", "completed\n"));
            require(timedOut.started() && timedOut.timedOut() && !timedOut.allPass(),
                    "timeoutが検出されませんでした: " + timedOut);

            System.out.println("project runner: すべて合格");
        } finally {
            deleteRecursively(source);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
