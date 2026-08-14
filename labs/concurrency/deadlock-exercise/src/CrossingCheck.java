import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * 直したコードを3段で確かめる。参照専用（変更しない）。
 *
 * <p>第1段: {@code checkout} と {@code restock} が、どちらも2つのロックを取っているか。
 * 片方だけにすると循環待ちは消えるが、課題の条件（両方を取る）を満たさない。
 *
 * <p>第2段: 2つの処理を同時に1回ずつ走らせる。{@link TableLock} が取得後に窓を開けるので、
 * 両方が1つ目のロックを同時に持つ。取得順が逆なら必ず詰まり、そろっていれば必ず通る。
 *
 * <p>第3段: 窓を閉じて多数回動かし、注文数＋在庫数が変わらないことを確かめる。
 * ロックを外して速くした実装は、ここで落ちる。
 */
public final class CrossingCheck {

    public static void main(String[] args) throws Exception {
        // ── 第1段: 両方のロックを取っているか（窓は閉じて速く済ませる）──────────
        TableLock.slowWindow = false;
        InventoryService probe = new InventoryService();
        int order0 = TableLock.acquisitions("OrderTableLock");
        int stock0 = TableLock.acquisitions("StockTableLock");
        probe.checkout();
        int order1 = TableLock.acquisitions("OrderTableLock");
        int stock1 = TableLock.acquisitions("StockTableLock");
        probe.restock();
        int order2 = TableLock.acquisitions("OrderTableLock");
        int stock2 = TableLock.acquisitions("StockTableLock");

        boolean checkoutBoth = order1 > order0 && stock1 > stock0;
        boolean restockBoth = order2 > order1 && stock2 > stock1;
        if (checkoutBoth && restockBoth) {
            System.out.println("RESULT\tboth-locks\tOK");
        } else {
            System.out.printf("RESULT\tboth-locks\tMISSING checkout=%s restock=%s%n",
                    checkoutBoth ? "both" : "not-both", restockBoth ? "both" : "not-both");
            System.out.flush();
            return;
        }

        // ── 第2段: 交差させて逆順を必ず暴く ────────────────────────────
        TableLock.slowWindow = true;
        InventoryService service = new InventoryService();
        int expected = service.total();
        Thread checkout = new Thread(service::checkout, "checkout-worker");
        Thread restock = new Thread(service::restock, "restock-worker");
        checkout.setDaemon(true);
        restock.setDaemon(true);
        checkout.start();
        restock.start();

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!checkout.isAlive() && !restock.isAlive()) {
                break;
            }
            if (threads.findDeadlockedThreads() != null) {
                System.out.println("RESULT\tcrossing\tDEADLOCK");
                System.out.flush();
                return;
            }
            Thread.sleep(20);
        }
        if (checkout.isAlive() || restock.isAlive()) {
            System.out.println("RESULT\tcrossing\tSTUCK");
            System.out.flush();
            return;
        }
        System.out.println("RESULT\tcrossing\tOK");

        // ── 第3段: 締め忘れ・ロック外しを暴く ──────────────────────────
        TableLock.slowWindow = false;
        int rounds = 20_000;
        Thread many1 = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                service.checkout();
            }
        }, "checkout-loop");
        Thread many2 = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                service.restock();
            }
        }, "restock-loop");
        many1.start();
        many2.start();
        many1.join(20_000);
        many2.join(20_000);
        if (many1.isAlive() || many2.isAlive()) {
            System.out.println("RESULT\tinvariant\tSTUCK");
            System.out.flush();
            return;
        }
        int actual = service.total();
        System.out.printf("RESULT\tinvariant\t%s%n",
                actual == expected ? "OK" : "BROKEN expected=" + expected + " actual=" + actual);
        System.out.flush();
    }
}
