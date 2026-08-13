package jq.runner;

import jq.content.RuntimeCheck;
import jq.content.RuntimeLabSpec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 実プロセスを伴うlabを、一時コピーと固定scriptの中だけで実行する。
 *
 * scriptは {@code JQ_CHECK<TAB>PASS|FAIL<TAB>id<TAB>message} を1項目1行で返す。
 * ポートとrun idはランナーが生成し、教材JSONや学習者の編集内容からコマンドを作らない。
 */
public final class RuntimeLabRunner {

    private static final Pattern SAFE_MESSAGE = Pattern.compile("[^\\r\\n\\t]{1,500}");
    private static final int REQUIREMENT_TIMEOUT_SECONDS = 5;
    private final ProjectRunner projectRunner = new ProjectRunner();

    public Result run(RuntimeLabSpec spec, Map<String, String> submittedFiles) {
        RequirementCheck requirements = checkRequirements(spec);
        if (!requirements.missing().isEmpty()) {
            return new Result(false, false, false, false, -1, "", false,
                    String.join("\n", requirements.missing()), 0, pendingChecks(spec));
        }

        int port;
        try {
            port = reserveLocalPort();
        } catch (IOException e) {
            return new Result(true, false, false, false, -1, "", false,
                    "localhostの空きポートを確保できません: " + e.getMessage(), 0,
                    pendingChecks(spec));
        }
        Map<String, String> environment = new HashMap<>(requirements.environment());
        environment.put("JQ_LAB_PORT", String.valueOf(port));
        environment.put("JQ_LAB_RUN_ID", "jq-" + UUID.randomUUID().toString().replace("-", ""));
        // appを起動したJDKと同じjava/javac/jcmd/jfrを固定scriptから使う。
        environment.put("PATH", jdkBin() + java.io.File.pathSeparator
                + System.getenv().getOrDefault("PATH", ""));
        ProjectRunner.Result execution = projectRunner.run(
                spec.workspace(), submittedFiles, environment);
        List<CheckResult> checks = parseChecks(spec, execution.output());
        boolean checksPass = checks.stream().allMatch(CheckResult::pass);
        boolean allPass = execution.started() && execution.allPass() && checksPass;
        return new Result(true, execution.started(), allPass, execution.timedOut(),
                execution.exitCode(), execution.output(), execution.truncated(), execution.error(),
                execution.durationMs(), checks);
    }

    private static RequirementCheck checkRequirements(RuntimeLabSpec spec) {
        List<String> missing = new ArrayList<>();
        boolean acceptsPodman = spec.requiredTools().contains("docker-or-podman");
        for (String tool : spec.requiredTools()) {
            if (tool.equals("docker-or-podman")) continue;
            List<String> command = switch (tool) {
                case "java", "javac" -> List.of(jdkTool(tool), "--version");
                case "jcmd" -> List.of(jdkTool(tool), "-h");
                case "jfr" -> List.of(jdkTool(tool), "help");
                case "mvn", "gradle" -> List.of(tool, "--version");
                case "docker" -> List.of("docker", "version", "--format", "{{.Server.Version}}");
                default -> throw new IllegalArgumentException("未許可のruntime toolです: " + tool);
            };
            if (!commandWorks(command)) missing.add(toolHelp(tool));
        }
        if (!missing.isEmpty()) return new RequirementCheck(List.copyOf(missing), Map.of());

        List<String> candidates = new ArrayList<>();
        if (acceptsPodman) {
            for (String runtime : List.of("docker", "podman")) {
                if (containerRuntimeWorks(runtime)) candidates.add(runtime);
            }
            if (candidates.isEmpty()) {
                missing.add(toolHelp("docker-or-podman"));
                return new RequirementCheck(List.copyOf(missing), Map.of());
            }
        } else if (spec.requiredTools().contains("docker")) {
            candidates.add("docker");
        }

        String selectedRuntime = null;
        for (String candidate : candidates) {
            boolean hasAllImages = spec.requiredImages().stream()
                    .allMatch(image -> commandWorks(List.of(candidate, "image", "inspect", image)));
            if (hasAllImages) {
                selectedRuntime = candidate;
                break;
            }
        }
        if (!candidates.isEmpty() && selectedRuntime == null) {
            StringBuilder message = new StringBuilder("必要なcontainer imageがありません。");
            for (String image : spec.requiredImages()) {
                if (acceptsPodman) {
                    message.append(" Dockerなら `docker pull ").append(image)
                            .append("`、Podmanなら `podman pull ").append(image).append("`。");
                } else {
                    message.append(" 先に `docker pull ").append(image).append("`を実行してください。");
                }
            }
            missing.add(message.toString());
            return new RequirementCheck(List.copyOf(missing), Map.of());
        }
        Map<String, String> environment = selectedRuntime == null
                ? Map.of() : Map.of("JQ_CONTAINER_RUNTIME", selectedRuntime);
        return new RequirementCheck(List.of(), environment);
    }

    private static boolean containerRuntimeWorks(String runtime) {
        return switch (runtime) {
            case "docker" -> commandWorks(
                    List.of("docker", "version", "--format", "{{.Server.Version}}"));
            case "podman" -> commandWorks(
                    List.of("podman", "info", "--format", "{{.Host.OS}}"));
            default -> false;
        };
    }

    private static String jdkBin() {
        return Path.of(System.getProperty("java.home"), "bin").toString();
    }

    private static String jdkTool(String name) {
        Path candidate = Path.of(jdkBin(), name);
        return Files.isExecutable(candidate) ? candidate.toString() : name;
    }

    private static boolean commandWorks(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return false;
        }
        try {
            boolean ended = process.waitFor(REQUIREMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            return ended && process.exitValue() == 0;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String toolHelp(String tool) {
        return switch (tool) {
            case "java", "javac", "jcmd", "jfr" -> "JDK 21の`" + tool
                    + "`を起動できません。JREではなくJDKをインストールし、PATHを確認してください。";
            case "mvn" -> "Mavenを起動できません。Maven 3.9以降をインストールしてください。";
            case "gradle" -> "Gradleを起動できません。GradleまたはWrapperを準備してください。";
            case "docker" -> "Docker daemonへ接続できません。Docker Desktop等を起動してください。";
            case "docker-or-podman" -> "Docker daemonまたはPodmanへ接続できません。"
                    + "Docker Desktop、Podman machine、またはrootless Podmanを起動してください。";
            default -> tool + "を起動できません。";
        };
    }

    private record RequirementCheck(List<String> missing, Map<String, String> environment) {
    }

    private static int reserveLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static List<CheckResult> parseChecks(RuntimeLabSpec spec, String output) {
        Map<String, CheckResult> reported = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.split("\\t", 4);
            if (parts.length != 4 || !parts[0].equals("JQ_CHECK")) continue;
            boolean pass;
            if (parts[1].equals("PASS")) pass = true;
            else if (parts[1].equals("FAIL")) pass = false;
            else continue;
            if (!parts[2].matches("[a-z0-9-]+") || !SAFE_MESSAGE.matcher(parts[3]).matches()) continue;
            // 重複報告は最後の成功で上書きさせず、不合格にする。
            if (reported.containsKey(parts[2])) {
                reported.put(parts[2], new CheckResult(parts[2], "検査結果が重複しています", false));
            } else {
                reported.put(parts[2], new CheckResult(parts[2], parts[3], pass));
            }
        }
        List<CheckResult> results = new ArrayList<>();
        for (RuntimeCheck expected : spec.checks()) {
            CheckResult actual = reported.get(expected.id());
            if (actual == null) {
                results.add(new CheckResult(expected.id(), expected.label() + "（結果が報告されませんでした）", false));
            } else {
                results.add(actual);
            }
        }
        return List.copyOf(results);
    }

    private static List<CheckResult> pendingChecks(RuntimeLabSpec spec) {
        return spec.checks().stream()
                .map(check -> new CheckResult(check.id(), check.label() + "（未実行）", false)).toList();
    }

    public record CheckResult(String id, String message, boolean pass) {
        public Map<String, Object> toJson() {
            return Map.of("id", id, "message", message, "pass", pass);
        }
    }

    public record Result(
            boolean available,
            boolean started,
            boolean allPass,
            boolean timedOut,
            int exitCode,
            String output,
            boolean truncated,
            String error,
            long durationMs,
            List<CheckResult> checks) {

        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("runtimeLab", true);
            json.put("available", available);
            json.put("started", started);
            json.put("allPass", allPass);
            json.put("timedOut", timedOut);
            json.put("exitCode", exitCode);
            json.put("output", output);
            json.put("truncated", truncated);
            json.put("error", error);
            json.put("durationMs", durationMs);
            json.put("checks", checks.stream().map(CheckResult::toJson).toList());
            json.put("passedCount", checks.stream().filter(CheckResult::pass).count());
            return json;
        }
    }
}
