import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * 必ずデッドロックする本番相当のコード。参照専用（直さない）。
 *
 * <p>注文テーブルと在庫テーブルの2つのロックを、2つの処理が<b>逆の順番</b>で取る。
 * 会計処理は 注文 → 在庫、補充処理は 在庫 → 注文 の順に取る。
 *
 * <p>ラッチで「両方が1つ目のロックを持った状態」を作ってから2つ目へ進むので、
 * デッドロックは<b>毎回必ず起きる</b>。たまたま再現しない、ということがない。
 *
 * <p>デッドロックを見つけたら、JMX（{@link ThreadMXBean#findDeadlockedThreads()}）で
 * 循環待ちの事実を確定させて印字する。これはJVMの実装によらず同じ結果になるので、
 * 採点はこの値を正解として使う。人が読む材料としては、別に取るスレッドダンプを見る。
 */
public final class DeadlockDemo {

    /** ロックの正体がスレッドダンプに出るよう、テーブルごとに別のクラスにしてある。 */
    static final class OrderTableLock { }

    static final class StockTableLock { }

    static final Object ORDER_TABLE = new OrderTableLock();
    static final Object STOCK_TABLE = new StockTableLock();

    public static void main(String[] args) throws Exception {
        CountDownLatch bothHoldFirst = new CountDownLatch(2);
        start("checkout-worker", ORDER_TABLE, STOCK_TABLE, bothHoldFirst);
        start("restock-worker", STOCK_TABLE, ORDER_TABLE, bothHoldFirst);

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 400; i++) {
            long[] deadlocked = threads.findDeadlockedThreads();
            if (deadlocked != null) {
                ThreadInfo[] infos = threads.getThreadInfo(deadlocked);
                Arrays.sort(infos, (a, b) -> a.getThreadName().compareTo(b.getThreadName()));
                StringBuilder names = new StringBuilder();
                for (ThreadInfo info : infos) {
                    if (names.length() > 0) {
                        names.append(',');
                    }
                    names.append(info.getThreadName());
                    // 待っているロックのクラス名と、それを持っているスレッド
                    System.out.printf("FACT\twaits.%s\t%s%n",
                            info.getThreadName(), simpleName(info.getLockInfo().getClassName()));
                    System.out.printf("FACT\towner.%s\t%s%n",
                            info.getThreadName(), info.getLockOwnerName());
                }
                System.out.printf("FACT\tblocked.threads\t%s%n", names);
                System.out.printf("FACT\tcycle.length\t%d%n", infos.length);
                System.out.println("DEADLOCK-READY");
                System.out.flush();
                // スレッドダンプを取る時間を渡してから終わる（採点側が止める）
                Thread.sleep(120_000);
                return;
            }
            Thread.sleep(25);
        }
        System.out.println("NO-DEADLOCK");
    }

    private static String simpleName(String className) {
        int dollar = className.lastIndexOf('$');
        return dollar < 0 ? className : className.substring(dollar + 1);
    }

    private static void start(String name, Object first, Object second, CountDownLatch latch) {
        Thread worker = new Thread(() -> {
            synchronized (first) {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (second) {
                    // ここへは到達しない
                }
            }
        }, name);
        worker.setDaemon(true);
        worker.start();
    }
}
