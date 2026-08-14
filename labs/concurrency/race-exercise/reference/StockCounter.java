/**
 * 在庫と出荷済み件数を持つ、共有される集計（模範解答）。
 *
 * <p>直し方は「不変条件を壊せる区間を1つにまとめて、そこを1スレッドずつ通す」。
 * 在庫の確認と2つの値の更新は**ひとつの操作**なので、途中で他のスレッドを入れてはいけない。
 * 読み出しも同じロックで守る。守らないと、更新の途中（合計が合わない状態）が見えてしまう。
 *
 * <p>{@code AtomicInteger}を2本にするのでは足りない。1本ずつは原子的でも、
 * 「在庫を減らしてから出荷を増やす」の間に他のスレッドが読めるので、合計が合わない瞬間ができる。
 * 数を2つ持つのではなく、**不変条件のかたまりを1つのロックで守る**のが要点。
 */
public class StockCounter {

    private final Object lock = new Object();

    private int stock;
    private int shipped;

    public StockCounter(int initialStock) {
        this.stock = initialStock;
    }

    /**
     * 在庫から{@code quantity}だけ出荷する。
     *
     * @return 出荷できたら {@code true}。在庫が足りなければ何も変えずに {@code false}
     */
    public boolean ship(int quantity) {
        synchronized (lock) {
            if (stock < quantity) {
                return false;
            }
            stock -= quantity;
            shipped += quantity;
            return true;
        }
    }

    /**
     * 在庫と出荷済み件数を、<b>同じ時点の値として</b>返す。
     *
     * @return {@code {stock, shipped}} の2要素
     */
    public int[] snapshot() {
        synchronized (lock) {
            return new int[] { stock, shipped };
        }
    }
}
