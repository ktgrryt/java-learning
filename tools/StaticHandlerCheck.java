package jq.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 静的配信がwebディレクトリ外の実体ファイルを公開しないことを回帰検査する。 */
public final class StaticHandlerCheck {

    private StaticHandlerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("jq-static-handler-");
        try {
            Path root = Files.createDirectory(base.resolve("web")).toRealPath();
            Path publicFile = Files.writeString(
                    root.resolve("index.html"), "public", StandardCharsets.UTF_8);
            Path secret = Files.writeString(
                    base.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);

            require(publicFile.equals(StaticHandler.resolveRegularFileUnder(root, "index.html")),
                    "web内の通常ファイルを解決できません");
            require(StaticHandler.resolveRegularFileUnder(root, "../secret.txt") == null,
                    "..によるweb外への脱出を拒否できません");

            Files.createSymbolicLink(root.resolve("leak.txt"), secret);
            require(StaticHandler.resolveRegularFileUnder(root, "leak.txt") == null,
                    "web内のsymlinkから外部ファイルを参照できてしまいます");

            Path outsideDirectory = Files.createDirectory(base.resolve("outside"));
            Files.writeString(outsideDirectory.resolve("nested.txt"), "nested secret");
            Files.createSymbolicLink(root.resolve("linked-directory"), outsideDirectory);
            require(StaticHandler.resolveRegularFileUnder(
                    root, "linked-directory/nested.txt") == null,
                    "web内のsymlink directoryから外部ファイルを参照できてしまいます");

            System.out.println("static handler security: すべて合格");
        } finally {
            deleteRecursively(base);
        }
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
