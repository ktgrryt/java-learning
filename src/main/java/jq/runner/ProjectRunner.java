package jq.runner;

import jq.content.ProjectFile;
import jq.content.ProjectSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 既存labを一時コピーし、許可された複数ファイルだけを書き換えて検証する。 */
public final class ProjectRunner {

    public static final int FILE_LIMIT_CHARS = 80_000;
    public static final int TOTAL_LIMIT_CHARS = 300_000;
    private static final int OUTPUT_LIMIT_BYTES = 60_000;

    public Result run(ProjectSpec spec, Map<String, String> submittedFiles) {
        return run(spec, submittedFiles, Map.of());
    }

    /** runtime-labだけが使う固定環境変数付き実行。値はサーバー側で生成する。 */
    Result run(ProjectSpec spec, Map<String, String> submittedFiles, Map<String, String> environment) {
        validateSubmission(spec, submittedFiles);
        Path workDir = null;
        long startedAt = System.nanoTime();
        try {
            workDir = Files.createTempDirectory("jq-project-");
            copyProject(spec, workDir);
            for (ProjectFile file : spec.editableFiles()) {
                Path destination = workDir.resolve(file.path()).normalize();
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, submittedFiles.get(file.path()), StandardCharsets.UTF_8);
            }
            return execute(spec, workDir, startedAt, environment);
        } catch (IOException e) {
            throw new UncheckedIOException("project問題の一時ディレクトリを準備できません", e);
        } finally {
            if (workDir != null) deleteRecursively(workDir);
        }
    }

    private static void validateSubmission(ProjectSpec spec, Map<String, String> submittedFiles) {
        Set<String> expected = spec.editableFiles().stream().map(ProjectFile::path)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!submittedFiles.keySet().equals(expected)) {
            throw new IllegalArgumentException("編集対象ファイルが一致しません。画面を再読み込みしてください。");
        }
        int total = 0;
        for (Map.Entry<String, String> entry : submittedFiles.entrySet()) {
            String content = entry.getValue();
            if (content == null) throw new IllegalArgumentException(entry.getKey() + " の内容がありません");
            if (content.length() > FILE_LIMIT_CHARS) {
                throw new IllegalArgumentException(entry.getKey() + " が長すぎます（上限 "
                        + FILE_LIMIT_CHARS + "文字）");
            }
            total += content.length();
        }
        if (total > TOTAL_LIMIT_CHARS) {
            throw new IllegalArgumentException("project全体が長すぎます（上限 " + TOTAL_LIMIT_CHARS + "文字）");
        }
    }

    private static void copyProject(ProjectSpec spec, Path workDir) throws IOException {
        try (Stream<Path> paths = Files.walk(spec.sourceDir())) {
            for (Path source : paths.sorted().toList()) {
                String relative = spec.sourceDir().relativize(source).toString().replace('\\', '/');
                if (relative.isEmpty() || excluded(relative, spec.excludedPaths())) continue;
                if (Files.isSymbolicLink(source)) continue;
                Path destination = workDir.resolve(relative).normalize();
                if (!destination.startsWith(workDir)) {
                    throw new IOException("project sourceに不正なパスがあります: " + relative);
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static boolean excluded(String relative, List<String> excluded) {
        for (String entry : excluded) {
            if (relative.equals(entry) || relative.startsWith(entry + "/")
                    || relative.contains("/" + entry + "/")) return true;
        }
        return false;
    }

    private static Result execute(ProjectSpec spec, Path workDir, long startedAt,
                                  Map<String, String> environment) {
        List<String> command = new ArrayList<>(spec.command());
        if (command.get(0).startsWith("./")) {
            command.set(0, workDir.resolve(command.get(0).substring(2)).toString());
        }
        ProcessBuilder builder = new ProcessBuilder(ProcessGroupCommand.wrap(command));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        // ビルドツールの対話・色制御を安定させ、教材の出力を読みやすくする。
        builder.environment().put("CI", "true");
        builder.environment().put("NO_COLOR", "1");
        builder.environment().putAll(environment);
        // 非対話bashの起動時hookを継承し、固定wrapperの外で任意scriptを実行しない。
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(false, false, false, -1, "", false,
                    toolError(spec.command().get(0), e), elapsedMs(startedAt));
        }
        OutputPump pump = new OutputPump(process.getInputStream());
        pump.start();
        boolean timedOut = false;
        int exitCode;
        try {
            if (process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS)) {
                exitCode = process.exitValue();
            } else {
                destroyRoot(process);
                closeProcessOutput(process);
                timedOut = true;
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            destroyRoot(process);
            closeProcessOutput(process);
            Thread.currentThread().interrupt();
            timedOut = true;
            exitCode = -1;
        }
        pump.finish();
        return new Result(true, !timedOut && exitCode == 0, timedOut, exitCode,
                pump.text(), pump.truncated(), "", elapsedMs(startedAt));
    }

    private static String toolError(String executable, IOException e) {
        if (executable.equals("mvn")) {
            return "Mavenを起動できません。Maven 3.9以降をインストールし、mvnをPATHへ追加してください。";
        }
        if (executable.equals("gradle")) {
            return "Gradleを起動できません。Gradleをインストールし、gradleをPATHへ追加してください。";
        }
        return "検証コマンドを起動できません: " + e.getMessage();
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static void closeProcessOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
    }

    private static void destroyRoot(Process process) {
        // runtime-labの固定scriptはTERM trapでserver/containerを片付ける。
        // 先に親へ通常の終了要求を送り、短い猶予のあと残ったプロセスだけ強制停止する。
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record Result(
            boolean started,
            boolean allPass,
            boolean timedOut,
            int exitCode,
            String output,
            boolean truncated,
            String error,
            long durationMs) {

        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("project", true);
            json.put("started", started);
            json.put("allPass", allPass);
            json.put("timedOut", timedOut);
            json.put("exitCode", exitCode);
            json.put("output", output);
            json.put("truncated", truncated);
            json.put("error", error);
            json.put("durationMs", durationMs);
            json.put("passedCount", allPass ? 1 : 0);
            return json;
        }
    }

    private static final class OutputPump extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        OutputPump(InputStream input) {
            super("jq-project-output");
            this.input = input;
            setDaemon(true);
        }

        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int count;
                while ((count = input.read(chunk)) != -1) {
                    int room = OUTPUT_LIMIT_BYTES - buffer.size();
                    if (room <= 0) {
                        truncated = true;
                        continue;
                    }
                    buffer.write(chunk, 0, Math.min(count, room));
                    if (count > room) truncated = true;
                }
            } catch (IOException ignored) { }
        }

        void finish() {
            try { join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        String text() { return buffer.toString(StandardCharsets.UTF_8); }
        boolean truncated() { return truncated; }
    }
}
