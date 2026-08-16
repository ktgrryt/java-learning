import jq.content.PreflightCheck;
import jq.content.PreflightSpec;
import jq.runner.JdkCapability;
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

        // JDK付属の診断ツール。`jcmd -h` の使用方法には数字が混ざるので、
        // 版として読んで「利用できます（0）」と出さないことも見る。
        PreflightRunner.Result jdkTools = runner.run(new PreflightSpec("確認", List.of(
                tool("jcmd", "jcmd", "", true))));
        require(jdkTools.ready(), "JDK付属の jcmd が見つかりませんでした: " + jdkTools);
        require(jdkTools.checks().getFirst().summary().equals("利用できます"),
                "jcmdの判定に版が混ざりました: " + jdkTools.checks().getFirst().summary());

        // JFRは「コマンドが在る」だけでは足りない。事前確認とruntime labが
        // **同じ実測**を使っていることを確かめる（別々に測ると食い違う）。
        boolean canRecord = JdkCapability.canRecordFlight();
        PreflightRunner.Result flight = runner.run(new PreflightSpec("確認", List.of(
                tool("jfr", "jfr", "", true))));
        PreflightRunner.CheckResult jfr = flight.checks().getFirst();
        require(jfr.pass() == canRecord,
                "JFRの判定がruntime labの実測と食い違いました: pass=" + jfr.pass()
                        + " / canRecordFlight=" + canRecord);
        require(flight.ready() == canRecord, "必須のJFR判定が準備完了へ反映されていません");
        if (!canRecord) {
            require(jfr.detail().contains("OpenJ9"),
                    "記録を作れない理由が学習者へ伝わりません: " + jfr.detail());
        }

        // 任意にすれば、記録を作れない配布物でも準備完了を妨げない。
        PreflightRunner.Result optionalFlight = runner.run(new PreflightSpec("確認", List.of(
                tool("optional-jfr", "jfr", "", false))));
        require(optionalFlight.ready(), "任意のJFR判定で準備未完了になりました: " + optionalFlight);

        System.out.println("preflight runner: すべて合格"
                + (canRecord ? "（このJDKはJFRを記録できます）"
                             : "（このJDKはJFRを記録できないので、その経路も確かめました）"));
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
