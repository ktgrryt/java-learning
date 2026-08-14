import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 複数の集計jobをまとめて実行するサービス。
 *
 * <p>いまの実装は、プールを作るだけで**呼び出し元のスレッドで順番に**実行している。
 * そのため次の4つが満たせていない。採点はそれぞれを別に測る。
 *
 * <ol>
 *   <li>jobを並行に実行する（{@code workers}本まで同時に走る）</li>
 *   <li>{@link #collectWithin}では、間に合わなかったjobを打ち切って{@code "TIMEOUT"}にする</li>
 *   <li>{@link #collect}では、jobが投げた例外を握りつぶさず呼び出し元へ伝える</li>
 *   <li>{@link #close}でプールを終わらせる（終わらせないとJVMが終了できない）</li>
 * </ol>
 *
 * <p>クラス名・メソッドの名前・引数・戻り値は採点の足場が呼ぶので変えないこと。
 * 中の作りは自由に変えてよい。
 */
public class ReportService implements AutoCloseable {

    /** 同時に走らせる本数。 */
    private final int workers;

    private final ExecutorService pool;

    public ReportService(int workers) {
        this.workers = workers;
        this.pool = Executors.newFixedThreadPool(workers);
        // プールのスレッドは、既定では最初の投入時に作られる。ここで先に作っておくと、
        // 終わらせ忘れが「JVMが終了できない」という形で必ず現れる（プールのスレッドは
        // daemonではないので、残っている間はJVMが終われない）。
        ((ThreadPoolExecutor) pool).prestartAllCoreThreads();
    }

    /**
     * 全jobを実行し、<b>投入した順</b>で結果を返す。
     *
     * @throws Exception jobが例外を投げたとき。原因が分かる形で伝えること
     */
    public List<String> collect(List<Callable<String>> jobs) throws Exception {
        // TODO: プールへ投入して並行に走らせる。失敗は握りつぶさず呼び出し元へ伝える
        List<String> results = new ArrayList<>();
        for (Callable<String> job : jobs) {
            try {
                results.add(job.call());
            } catch (Exception ignored) {
                results.add("ERROR");
            }
        }
        return results;
    }

    /**
     * 全jobを実行し、<b>投入した順</b>で結果を返す。
     * {@code timeoutMillis}以内に終わらなかったjobは打ち切り、その位置を{@code "TIMEOUT"}にする。
     *
     * <p>打ち切りで起きた例外は失敗として扱わない（{@code "TIMEOUT"}にする）。
     * 制限時間を過ぎたら、遅いjobを待たずに戻ること。
     */
    public List<String> collectWithin(List<Callable<String>> jobs, long timeoutMillis)
            throws Exception {
        // TODO: 制限時間を渡して投入し、間に合わなかったjobは打ち切って "TIMEOUT" にする
        return collect(jobs);
    }

    /** プールを終わらせる。 */
    @Override
    public void close() {
        // TODO: 受付を止め、走っているjobの終了を待つ。待っても終わらなければ止める
    }
}
