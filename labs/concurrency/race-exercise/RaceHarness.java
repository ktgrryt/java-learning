import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link StockCounter} を多数のスレッドで同時に呼び、壊れ方を3つに分けて数える足場。参照専用。
 *
 * <p>採点できるようにするため、壊れるかどうかを運に任せない。
 *
 * <ul>
 *   <li>{@link CyclicBarrier}で8スレッドを<b>同じ瞬間に</b>走り出させる。
 *       ばらばらに始めると、速いスレッドが終わってから次が始まってしまい競合しない。</li>
 *   <li>1スレッド50,000回、20回戦。守っていない実装なら、この回数で必ず取りこぼしが出る。</li>
 *   <li>在庫は「全要求より1,000少ない」数にしておく。足りない分は{@code false}が返るはずで、
 *       在庫の確認と更新がばらばらだと<b>在庫を超えて出荷</b>してしまう。</li>
 * </ul>
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class RaceHarness {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 50_000;
    private static final int REQUESTED = THREADS * PER_THREAD;
    /** 在庫は要求より少なくしておく。この1,000回ぶんは在庫切れで断られるのが正しい。 */
    private static final int SHORTAGE = 1_000;
    private static final int STOCK = REQUESTED - SHORTAGE;
    private static final int ROUNDS = 20;

    /** 1回戦の観測結果。 */
    private record Round(int stock, int shipped, int successes, long torn, long oversold) { }

    public static void main(String[] args) throws Exception {
        int lostRounds = 0;
        int tornRounds = 0;
        int oversellRounds = 0;
        long lostTotal = 0;
        long tornTotal = 0;
        long oversoldTotal = 0;
        String lostDetail = "";
        String tornDetail = "";
        String oversellDetail = "";

        for (int round = 1; round <= ROUNDS; round++) {
            Round result = runRound();

            // 更新の取りこぼし: 成功した回数と shipped が合わない、または最後の在庫が0でない
            if (result.shipped() != result.successes() || result.stock() != STOCK - result.shipped()
                    || result.successes() < STOCK) {
                lostRounds++;
                lostTotal += Math.abs(result.successes() - result.shipped());
                if (lostDetail.isEmpty()) {
                    lostDetail = round + "回戦で 成功" + result.successes() + "回 に対して shipped="
                            + result.shipped() + " stock=" + result.stock();
                }
            }
            // 引き裂かれた観測: stock + shipped が最初の在庫と合わない瞬間が見えた
            if (result.torn() > 0) {
                tornRounds++;
                tornTotal += result.torn();
                if (tornDetail.isEmpty()) {
                    tornDetail = round + "回戦で" + result.torn() + "回、合計が" + STOCK + "でない状態が見えた";
                }
            }
            // 在庫超え: 在庫が負になった、または在庫数より多く出荷できてしまった
            if (result.oversold() > 0 || result.successes() > STOCK) {
                oversellRounds++;
                oversoldTotal += Math.max(result.oversold(), result.successes() - STOCK);
                if (oversellDetail.isEmpty()) {
                    oversellDetail = round + "回戦で在庫" + STOCK + "に対して成功" + result.successes()
                            + "回（負の在庫を" + result.oversold() + "回観測）";
                }
            }
        }

        print("rounds", String.valueOf(ROUNDS));
        print("requested", String.valueOf(REQUESTED));
        print("stock", String.valueOf(STOCK));
        print("lost-rounds", String.valueOf(lostRounds));
        print("lost-total", String.valueOf(lostTotal));
        print("lost-detail", lostDetail);
        print("torn-rounds", String.valueOf(tornRounds));
        print("torn-total", String.valueOf(tornTotal));
        print("torn-detail", tornDetail);
        print("oversell-rounds", String.valueOf(oversellRounds));
        print("oversell-total", String.valueOf(oversoldTotal));
        print("oversell-detail", oversellDetail);
    }

    private static Round runRound() throws Exception {
        StockCounter counter = new StockCounter(STOCK);
        CyclicBarrier start = new CyclicBarrier(THREADS);
        AtomicInteger successes = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong torn = new AtomicLong();
        AtomicLong oversold = new AtomicLong();

        // 更新の途中が見えていないかを、別のスレッドから覗き続ける
        Thread observer = new Thread(() -> {
            while (running.get()) {
                int[] seen = counter.snapshot();
                if (seen.length != 2 || seen[0] + seen[1] != STOCK) torn.incrementAndGet();
                if (seen.length == 2 && seen[0] < 0) oversold.incrementAndGet();
            }
        }, "race-observer");
        observer.setDaemon(true);
        observer.start();

        Thread[] workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            workers[i] = new Thread(() -> {
                int local = 0;
                try {
                    start.await();
                } catch (Exception stop) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < PER_THREAD; n++) {
                    if (counter.ship(1)) local++;
                }
                successes.addAndGet(local);
            }, "race-worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) worker.join();
        running.set(false);
        observer.join(1_000);

        int[] end = counter.snapshot();
        return new Round(end.length == 2 ? end[0] : Integer.MIN_VALUE,
                end.length == 2 ? end[1] : Integer.MIN_VALUE,
                successes.get(), torn.get(), oversold.get());
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t" + value);
    }
}
