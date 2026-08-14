import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ReportService} の並行度・打ち切り・例外の伝わり方を測る足場。参照専用。
 *
 * <p>時間で判定する項目は、逐次実行との差が2倍以上開く値だけを見る（速い機械でも遅い機械でも
 * 結果が変わらないようにするため）。プールの終了は別JVMで測るので、ここでは扱わない
 * （{@link ExitProbe} を参照）。
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class TaskHarness {

    private static final int WORKERS = 4;

    public static void main(String[] args) {
        parallel();
        timeout();
        error();
        // 測定は終わっている。ここで待たずに落とす。
        // 終わらせ忘れたプールが残っていると、このJVMは自力では終了できない。
        // それを測るのは別JVM（ExitProbe）の仕事なので、ここで巻き込まれないようにする。
        System.out.flush();
        System.exit(0);
    }

    /** 8件のjobが本当に同時に走ったか。逐次なら1件ずつしか走らない。 */
    private static void parallel() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "r" + i;
            jobs.add(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(150);
                    return name;
                } finally {
                    running.decrementAndGet();
                }
            });
        }
        long startedAt = System.nanoTime();
        List<String> results;
        try (ReportService service = new ReportService(WORKERS)) {
            results = service.collect(jobs);
        } catch (Exception failed) {
            print("parallel-error", failed.toString());
            return;
        }
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        print("parallel-peak", String.valueOf(peak.get()));
        print("parallel-millis", String.valueOf(elapsed));
        print("parallel-order", String.join(",", results));
    }

    /** 遅いjobを制限時間で打ち切り、間に合ったjobの結果は返せるか。 */
    private static void timeout() {
        List<Callable<String>> jobs = List.of(
                () -> { Thread.sleep(50); return "fast-1"; },
                () -> { Thread.sleep(4_000); return "slow-1"; },
                () -> { Thread.sleep(50); return "fast-2"; },
                () -> { Thread.sleep(4_000); return "slow-2"; });
        long startedAt = System.nanoTime();
        List<String> results;
        try (ReportService service = new ReportService(WORKERS)) {
            results = service.collectWithin(jobs, 600);
        } catch (Exception failed) {
            print("timeout-error", failed.toString());
            return;
        }
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        print("timeout-millis", String.valueOf(elapsed));
        print("timeout-order", String.join(",", results));
    }

    /** 失敗したjobの原因が、呼び出し元まで届くか。 */
    private static void error() {
        List<Callable<String>> jobs = List.of(
                () -> "ok-1",
                () -> { throw new IllegalStateException("boom-42"); },
                () -> "ok-2");
        try (ReportService service = new ReportService(WORKERS)) {
            List<String> results = service.collect(jobs);
            // 例外が投げられなければ、握りつぶしている
            print("error-thrown", "none");
            print("error-results", String.join(",", results));
        } catch (Exception thrown) {
            print("error-thrown", thrown.getClass().getSimpleName());
            print("error-message", describe(thrown));
        }
    }

    /** 例外とその原因のメッセージを1行にまとめる（"boom-42" が残っているかを見るため）。 */
    private static String describe(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (!text.isEmpty()) text.append(" <- ");
            text.append(current.getClass().getSimpleName()).append(':').append(current.getMessage());
            if (current.getCause() == current) break;
        }
        return text.toString();
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t" + value.replace('\t', ' ').replace('\n', ' '));
    }
}
