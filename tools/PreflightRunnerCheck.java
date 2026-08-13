import jq.content.PreflightCheck;
import jq.content.PreflightSpec;
import jq.runner.PreflightRunner;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

/** 事前確認の版判定、空きポート、必須・任意の扱いを回帰検査する。 */
public final class PreflightRunnerCheck {

    private PreflightRunnerCheck() {
    }

    public static void main(String[] args) throws Exception {
        int availablePort;
        try (ServerSocket finder = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            availablePort = finder.getLocalPort();
        }

        PreflightRunner runner = new PreflightRunner();
        PreflightRunner.Result ready = runner.run(new PreflightSpec("確認", List.of(
                tool("java", "java", "21", true),
                port("available", availablePort, true),
                tool("optional-docker", "docker", "", false))));
        require(ready.ready(), "任意toolの不足で準備未完了になりました: " + ready);
        require(ready.checks().size() == 3, "check数が一致しません");

        PreflightRunner.Result oldVersion = runner.run(new PreflightSpec("確認", List.of(
                tool("future-java", "java", "999", true))));
        require(!oldVersion.ready() && !oldVersion.checks().getFirst().pass(),
                "最低版を満たさないtoolが合格しました");

        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int occupiedPort = occupied.getLocalPort();
            PreflightRunner.Result unavailable = runner.run(new PreflightSpec("確認", List.of(
                    port("occupied", occupiedPort, true))));
            require(!unavailable.ready() && !unavailable.checks().getFirst().pass(),
                    "使用中のポートが空きとして判定されました");
        }

        System.out.println("preflight runner: すべて合格");
    }

    private static PreflightCheck tool(String id, String tool, String minimum, boolean required) {
        return new PreflightCheck(id, "tool", id, required, tool, minimum, 0, "対処");
    }

    private static PreflightCheck port(String id, int port, boolean required) {
        return new PreflightCheck(id, "port", id, required, "", "", port, "対処");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
