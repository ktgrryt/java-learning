import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 複数の集計jobをまとめて実行するサービス（模範解答）。
 *
 * <p>要点は4つ。
 *
 * <ul>
 *   <li>自分でThreadを作らず、プールへ<b>タスクを投入する</b>。
 *       {@code invokeAll}なら投入順のFutureが返るので、結果の順番も揃う。</li>
 *   <li>制限時間つきの{@code invokeAll}は、間に合わなかったタスクを取り消す。
 *       取り消されたFutureの{@code get}は{@link CancellationException}を投げるので、
 *       これを{@code "TIMEOUT"}に読み替える。</li>
 *   <li>失敗は{@link ExecutionException}に包まれて届く。包みを剥がして<b>原因を投げ直す</b>。
 *       ここで握りつぶすと、呼び出し元は失敗に気づけない。</li>
 *   <li>{@code close}で受付を止め、終了を待つ。待っても終わらなければ割り込んで止める。
 *       プールのスレッドはdaemonではないので、終わらせないとJVMが終了できない。</li>
 * </ul>
 */
public class ReportService implements AutoCloseable {

    private final int workers;

    private final ExecutorService pool;

    public ReportService(int workers) {
        this.workers = workers;
        this.pool = Executors.newFixedThreadPool(workers);
        ((ThreadPoolExecutor) pool).prestartAllCoreThreads();
    }

    /**
     * 全jobを実行し、<b>投入した順</b>で結果を返す。
     *
     * @throws Exception jobが例外を投げたとき。原因が分かる形で伝えること
     */
    public List<String> collect(List<Callable<String>> jobs) throws Exception {
        List<String> results = new ArrayList<>();
        for (Future<String> done : pool.invokeAll(jobs)) {
            try {
                results.add(done.get());
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Exception checked) throw checked;
                throw failed;
            }
        }
        return results;
    }

    /**
     * 全jobを実行し、<b>投入した順</b>で結果を返す。
     * {@code timeoutMillis}以内に終わらなかったjobは打ち切り、その位置を{@code "TIMEOUT"}にする。
     */
    public List<String> collectWithin(List<Callable<String>> jobs, long timeoutMillis)
            throws Exception {
        List<String> results = new ArrayList<>();
        for (Future<String> done : pool.invokeAll(jobs, timeoutMillis, TimeUnit.MILLISECONDS)) {
            try {
                results.add(done.get());
            } catch (CancellationException | ExecutionException stopped) {
                // 打ち切られたjobは失敗ではない。割り込みで中断した場合も同じ扱いにする
                results.add("TIMEOUT");
            }
        }
        return results;
    }

    /** プールを終わらせる。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
