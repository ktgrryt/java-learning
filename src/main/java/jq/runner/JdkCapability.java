package jq.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JDKの配布物ごとに「コマンドは在るのに使えない」ものを、実際に動かして確かめる。
 *
 * <p>版やコマンドの有無だけでは足りない機能がある。JFRがその代表で、OpenJ9系の配布物は
 * `jfr` コマンドを同梱していても、JVM側が設定つきの記録を作れない（起動オプションを
 * 受け付けても記録fileを作らない）。だから「在るか」ではなく「作れるか」を測る。
 *
 * <p>この判定は <b>事前確認（{@link PreflightRunner}）とruntime lab
 * （{@link RuntimeLabRunner}）の両方から呼ぶ</b>。片方だけが独自に測ると、
 * 事前確認は通るのにlabが環境不足になる（またはその逆）という食い違いが起きる。
 */
public final class JdkCapability {

    /** 記録の実測はJVMを1つ起動するので、コマンドの有無より長く待つ。 */
    private static final int PROBE_TIMEOUT_SECONDS = 20;

    private JdkCapability() {
    }

    /** 短い記録を実際に作り、この配布物のJVMでJFRが使えるかを確かめる。 */
    public static boolean canRecordFlight() {
        Path probe;
        try {
            probe = Files.createTempDirectory("jq-jfr-probe-");
        } catch (IOException e) {
            return false;
        }
        Path recording = probe.resolve("probe.jfr");
        try {
            // 教材のlabと同じく設定を指定して記録する。OpenJ9はここで起動オプションを拒否する。
            // 起動オプションを受け付けても記録fileを作らない配布物があるため、file自体も確かめる。
            boolean started = commandWorks(List.of(jdkTool("java"),
                    "-XX:StartFlightRecording=filename=" + recording
                            + ",settings=profile,duration=1s",
                    "-version"));
            return started && Files.size(recording) > 0;
        } catch (IOException recordingMissing) {
            return false;
        } finally {
            deleteRecursively(probe);
        }
    }

    /** 実行中のJVMと同じ配布物の道具を使う。PATHの別のJDKを混ぜないため。 */
    static String jdkTool(String name) {
        Path candidate = Path.of(System.getProperty("java.home"), "bin", name);
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
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes();
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 消せなくても判定は変わらない。一時領域はOSが後で回収する。
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
    }
}
