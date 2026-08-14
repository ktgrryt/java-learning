/**
 * 注文テーブルと在庫テーブルの両方を触る2つの処理。
 *
 * <p>いま起きていること: {@link #checkout()} は 注文 → 在庫、{@link #restock()} は
 * 在庫 → 注文 の順にロックを取っている。**取得順が逆**なので、両方が同時に動くと
 * それぞれが相手の持っているロックを待ち、どちらも進めなくなる。
 *
 * <p>直すときの約束。
 *
 * <ul>
 *   <li>どちらの処理でも<b>同じ順番</b>でロックを取る。順番を1つ決めれば循環待ちは作れない。</li>
 *   <li>2つのロックは両方とも取る（片方をやめると、下の数え上げが壊れる）。</li>
 *   <li>取ったロックは必ず解放する（{@code try} / {@code finally}）。</li>
 * </ul>
 *
 * <p>ロックを外して速くするのは解決ではない。{@link #orders} と {@link #stock} の合計が
 * 変わらないことも採点する。
 */
public final class InventoryService {

    private final TableLock orderTable = new TableLock("OrderTableLock");
    private final TableLock stockTable = new TableLock("StockTableLock");

    private int orders;
    private int stock = 1_000_000;

    /** 注文を1件受け付け、在庫を1つ減らす。 */
    public void checkout() {
        // TODO: ロックの取得順を restock とそろえる
        orderTable.lock();
        try {
            stockTable.lock();
            try {
                orders++;
                stock--;
            } finally {
                stockTable.unlock();
            }
        } finally {
            orderTable.unlock();
        }
    }

    /** 補充で在庫を1つ増やし、受け付け済みの注文を1件取り消す。 */
    public void restock() {
        // TODO: ロックの取得順を checkout とそろえる
        stockTable.lock();
        try {
            orderTable.lock();
            try {
                stock++;
                orders--;
            } finally {
                orderTable.unlock();
            }
        } finally {
            stockTable.unlock();
        }
    }

    /** 注文数＋在庫数。ロックが効いていれば、何回動かしても変わらない。 */
    public int total() {
        return orders + stock;
    }
}
