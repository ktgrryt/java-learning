import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@link ReportService} を使って閉じたあと、JVMが自力で終了できるかを見る足場。参照専用。
 *
 * <p>プールのスレッドはdaemonではないので、終わらせないまま{@code main}を抜けても
 * <b>JVMは終了しない</b>。これは「アプリが終わらない」「コンテナが止まらない」として
 * 現場に出る形そのままなので、別のJVMで走らせて<b>本当に終了するか</b>を測る。
 *
 * <p>{@code EXIT-READY}を出したあと、このクラスは何もしない。終了できるかどうかは
 * run-runtime-lab.sh がプロセスの生存で判定する。
 */
public final class ExitProbe {

    public static void main(String[] args) throws Exception {
        List<Callable<String>> jobs = List.of(() -> "a", () -> "b", () -> "c", () -> "d");
        ReportService service = new ReportService(4);
        try {
            service.collect(jobs);
        } catch (Exception ignored) {
            // ここでの失敗は別の検査で報告する。この足場は終了できるかだけを見る
        }
        service.close();
        System.out.println("EXIT-READY");
        System.out.flush();
    }
}
