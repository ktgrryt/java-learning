import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 採点で使う実測。学習者は編集しない。
 *
 * ヒープの保持量、追い出したオブジェクトの回収、キャッシュの振る舞い、深い入力での
 * スタックを、実際のJVMから読み取る。GCログの形式はJVM実装で違うので使わず、
 * どのJVMでも同じ意味を持つ標準のJMXと標準のエラーだけを見る。
 */
public class HeapProbe {
    /** 投入する件数。1件2KBなので、保持し続けると40MB前後になる。 */
    private static final int PUTS = 20_000;
    private static final int PAYLOAD_BYTES = 2048;
    /** GC後にこれ以下なら「保持が有界」と見なす。上限付きなら数MBで収まる。 */
    private static final long MAX_RETAINED_MB = 24;
    private static final int DEEP_INPUT = 200_000;

    private static boolean failed;

    /**
     * 測り終わるまでキャッシュを到達可能にしておく置き場。
     *
     * ローカル変数はスコープの終わりではなく「最後に使った時点」で到達不能になりうる。
     * 置き場へ入れておかないと、測る前にキャッシュごと回収され、リークしている実装でも
     * 「保持していない」と見えてしまう。
     */
    private static final List<Object> KEEP_ALIVE = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        RecentOrders orders = new RecentOrders();
        KEEP_ALIVE.add(orders);
        for (long id = 0; id < PUTS; id++) {
            orders.put(id, new byte[PAYLOAD_BYTES]);
        }

        // 1. GCのあとに残っている量を測る。参照が残っていれば回収されない。
        long retained = usedHeapAfterGc();
        long gcCount = gcCount();
        report("heap-retained", retained <= MAX_RETAINED_MB * 1024 * 1024,
                PUTS + "件を投入したあと、GC後の使用ヒープは" + mb(retained)
                        + "MBでした（GC " + gcCount + "回）",
                PUTS + "件を投入したあと、GC後も" + mb(retained) + "MB残っています（上限"
                        + MAX_RETAINED_MB + "MB、GC " + gcCount
                        + "回）。古い注文への参照が残っていると回収できません");

        // 2. 追い出したエントリが回収できるか。参照が残っていればWeakReferenceは切れない。
        RecentOrders fresh = new RecentOrders();
        KEEP_ALIVE.add(fresh);
        byte[] evicted = new byte[PAYLOAD_BYTES];
        WeakReference<byte[]> watch = new WeakReference<>(evicted);
        fresh.put(-1L, evicted);
        evicted = null;
        for (long id = 0; id < RecentOrders.CAPACITY + 50; id++) {
            fresh.put(id, new byte[PAYLOAD_BYTES]);
        }
        boolean collected = false;
        for (int attempt = 0; attempt < 20 && !collected; attempt++) {
            System.gc();
            Thread.sleep(50);
            collected = watch.get() == null;
        }
        report("heap-evicted-collectable", collected,
                "追い出した注文がGCで回収されました",
                "追い出した注文がGCで回収されません。どこかに参照が残っています");

        // 3. キャッシュとしての振る舞い。新しいものが残り、古いものは消える。
        RecentOrders window = new RecentOrders();
        KEEP_ALIVE.add(window);
        for (long id = 0; id < RecentOrders.CAPACITY * 3; id++) {
            window.put(id, new byte[16]);
        }
        long newest = RecentOrders.CAPACITY * 3L - 1;
        long oldest = 0L;
        boolean behaviour = window.size() == RecentOrders.CAPACITY
                && window.get(newest) != null
                && window.get(oldest) == null;
        report("cache-window", behaviour,
                "直近" + RecentOrders.CAPACITY + "件だけが残りました",
                "size()は" + RecentOrders.CAPACITY + "件、最新は取り出せ、最古はnullになるようにしてください"
                        + "（実際: size=" + window.size()
                        + " 最新=" + (window.get(newest) != null ? "あり" : "なし")
                        + " 最古=" + (window.get(oldest) != null ? "あり" : "なし") + "）");

        // 4. 深い入力でスタックを使い切らないか。
        List<Long> amounts = new ArrayList<>(DEEP_INPUT);
        for (int i = 0; i < DEEP_INPUT; i++) {
            amounts.add(1L);
        }
        String stackResult;
        try {
            long total = OrderTotals.sum(amounts);
            stackResult = total == DEEP_INPUT ? "ok" : "合計が" + total + "になりました";
        } catch (StackOverflowError e) {
            stackResult = "StackOverflowError";
        }
        report("stack-deep-input", stackResult.equals("ok"),
                DEEP_INPUT + "件の入力でもスタックを使い切らずに合計を返しました",
                DEEP_INPUT + "件の入力で失敗します（" + stackResult
                        + "）。1件ごとにフレームを積まない形へ直してください");

        if (failed) System.exit(1);
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long gcCount() {
        long total = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, bean.getCollectionCount());
        }
        return total;
    }

    private static long mb(long bytes) {
        return bytes / 1024 / 1024;
    }

    private static void report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        if (!pass) failed = true;
    }
}
