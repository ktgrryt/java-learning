package jq.runner;

import jq.content.PreflightCheck;
import jq.content.PreflightSpec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 外部教材へ進む前に、許可済みツールとlocalhostのポートだけを実測する。 */
public final class PreflightRunner {

    private static final Pattern VERSION = Pattern.compile(
            "(?<![0-9])([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?");
    private static final int TIMEOUT_SECONDS = 5;
    private static final int OUTPUT_LIMIT_BYTES = 12_000;

    public Result run(PreflightSpec spec) {
        List<CheckResult> results = new ArrayList<>();
        for (PreflightCheck check : spec.checks()) {
            results.add(check.type().equals("tool") ? checkTool(check) : checkPort(check));
        }
        boolean ready = results.stream().allMatch(result -> !result.required() || result.pass());
        return new Result(ready, List.copyOf(results));
    }

    private static CheckResult checkTool(PreflightCheck check) {
        if (check.tool().equals("docker-or-podman")) return checkContainerRuntime(check);
        if (check.tool().equals("jfr")) return checkFlightRecorder(check);
        if (check.tool().equals("jcmd")) return checkJdkCommand(check);
        return runTool(check, toolCommand(check.tool()));
    }

    /**
     * JFRは「`jfr` コマンドが在る」だけでは足りない。
     *
     * OpenJ9系の配布物はコマンドを同梱していてもJVM側が設定つきの記録を作れず、章の
     * <b>必須labが必ず落ちる</b>。しかも `jfr` は在るので、学習者は要件を満たしていると
     * 見える。だから {@link JdkCapability} で短い記録を実際に作り、
     * <b>章に入る前にlabと同じ判定</b>を見せる。
     */
    private static CheckResult checkFlightRecorder(PreflightCheck check) {
        CheckResult attempt = runTool(check, toolCommand(check.tool()));
        if (!attempt.pass()) return attempt;
        if (!JdkCapability.canRecordFlight()) {
            return failed(check, "このJDKでは記録を作れません",
                    "`jfr` コマンドはありますが、`-XX:StartFlightRecording` で記録ファイルを"
                            + "作れませんでした。OpenJ9系の配布物で起きます。");
        }
        return passed(check, "記録を作れます", "短い記録を実際に作って確かめました。");
    }

    /**
     * 版を表示しないJDK付属ツールの確認。
     *
     * `jcmd -h` の使用方法には `0` のような数字が混ざるので、版として読むと
     * 「利用できます（0）」と出てしまう。ここでは使えるかどうかだけを見る。
     */
    private static CheckResult checkJdkCommand(PreflightCheck check) {
        CheckResult attempt = runTool(check, toolCommand(check.tool()));
        if (!attempt.pass()) return attempt;
        return passed(check, "利用できます", attempt.detail());
    }

    /**
     * Docker/Podmanのどちらでも良いlabのための確認。
     *
     * どちらかが応答すれば合格にする。片方しか入れていない学習者へ、
     * 入れていない方の導入を促さないための分岐である。
     */
    private static CheckResult checkContainerRuntime(PreflightCheck check) {
        List<String> failures = new ArrayList<>();
        for (String runtime : List.of("docker", "podman")) {
            CheckResult attempt = runTool(check,
                    List.of(runtime, "version", "--format", "{{.Server.Version}}"));
            if (attempt.pass()) {
                return passed(check, runtime + "を利用できます（" + attempt.detail() + "）",
                        runtime + "が応答しました。もう一方は入っていなくてかまいません。");
            }
            failures.add(runtime + ": " + attempt.summary());
        }
        return failed(check, "DockerもPodmanも利用できません", String.join(" / ", failures));
    }

    private static CheckResult runTool(PreflightCheck check, List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return failed(check, "見つかりません", command.get(0) + " を起動できませんでした。");
        }

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failed(check, "応答がありません", TIMEOUT_SECONDS + "秒で確認を停止しました。");
            }
            byte[] bytes = process.getInputStream().readNBytes(OUTPUT_LIMIT_BYTES);
            String output = new String(bytes, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return failed(check, "利用できません", firstLines(output));
            }
            String version = firstVersion(output);
            if (!check.minimumVersion().isEmpty()
                    && (version.isEmpty() || compareVersions(version, check.minimumVersion()) < 0)) {
                String actual = version.isEmpty() ? "版を判定できません" : "検出: " + version;
                return failed(check, check.minimumVersion() + "以上が必要です", actual);
            }
            String summary = version.isEmpty() ? "利用できます" : "利用できます（" + version + "）";
            return passed(check, summary, firstLines(output));
        } catch (IOException e) {
            return failed(check, "出力を読めません", e.getMessage());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return failed(check, "確認を中断しました", "もう一度実行してください。");
        }
    }

    private static CheckResult checkPort(PreflightCheck check) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), check.port()));
            return passed(check, "localhost:" + check.port() + " を利用できます",
                    "開発サーバーをこのポートで起動できます。");
        } catch (IOException | SecurityException e) {
            return failed(check, "localhost:" + check.port() + " は使用中です",
                    "別のプロセスを停止するか、labのポート設定を変更してください。");
        }
    }

    private static List<String> toolCommand(String tool) {
        return switch (tool) {
            case "java" -> List.of(javaTool("java"), "--version");
            case "javac" -> List.of(javaTool("javac"), "--version");
            // JDK付属の診断ツール。--version を持たないので help の起動だけを見る。
            case "jcmd" -> List.of(javaTool("jcmd"), "-h");
            case "jfr" -> List.of(javaTool("jfr"), "help");
            case "maven" -> List.of("mvn", "--version");
            case "gradle" -> List.of("gradle", "--version");
            case "docker" -> List.of("docker", "version", "--format", "{{.Server.Version}}");
            default -> throw new IllegalArgumentException("未許可の事前確認toolです: " + tool);
        };
    }

    private static String javaTool(String name) {
        Path bundled = Path.of(System.getProperty("java.home"), "bin", name);
        return Files.isExecutable(bundled) ? bundled.toString() : name;
    }

    static int compareVersions(String actual, String minimum) {
        int[] left = versionParts(actual);
        int[] right = versionParts(minimum);
        for (int i = 0; i < 3; i++) {
            int compared = Integer.compare(left[i], right[i]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int[] versionParts(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) return new int[] {0, 0, 0};
        return new int[] {
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
        };
    }

    private static String firstVersion(String output) {
        Matcher matcher = VERSION.matcher(output);
        if (!matcher.find()) return "";
        StringBuilder version = new StringBuilder(matcher.group(1));
        if (matcher.group(2) != null) version.append('.').append(matcher.group(2));
        if (matcher.group(3) != null) version.append('.').append(matcher.group(3));
        return version.toString();
    }

    private static String firstLines(String output) {
        if (output == null || output.isBlank()) return "出力はありません。";
        String[] lines = output.split("\\R");
        String text = String.join("\n", java.util.Arrays.copyOf(lines, Math.min(lines.length, 3)));
        return text.length() > 800 ? text.substring(0, 800) + "…" : text;
    }

    private static CheckResult passed(PreflightCheck check, String summary, String detail) {
        return new CheckResult(check.id(), check.label(), check.required(), true,
                summary, detail, check.help());
    }

    private static CheckResult failed(PreflightCheck check, String summary, String detail) {
        return new CheckResult(check.id(), check.label(), check.required(), false,
                summary, detail == null ? "" : detail, check.help());
    }

    public record Result(boolean ready, List<CheckResult> checks) {
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("preflight", true);
            json.put("ready", ready);
            json.put("checks", checks.stream().map(CheckResult::toJson).toList());
            return json;
        }
    }

    public record CheckResult(String id, String label, boolean required, boolean pass,
                              String summary, String detail, String help) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("label", label);
            json.put("required", required);
            json.put("pass", pass);
            json.put("summary", summary);
            json.put("detail", detail);
            json.put("help", help);
            return json;
        }
    }
}
