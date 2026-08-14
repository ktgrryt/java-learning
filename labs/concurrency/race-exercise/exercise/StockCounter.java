/**
 * 在庫と出荷済み件数を持つ、共有される集計。
 *
 * <p>1つのインスタンスを複数のスレッドが同時に呼ぶ。いまの実装は1スレッドなら正しいが、
 * 同時に呼ばれると壊れる。壊れ方は3つあり、採点はそれぞれを別に測る。
 *
 * <p>守るべき不変条件（どのスレッドから見ても、いつでも成り立つこと）:
 *
 * <ul>
 *   <li>{@code stock + shipped} は最初の在庫数から変わらない</li>
 *   <li>{@code stock} は0より小さくならない（在庫を超えて出荷しない）</li>
 *   <li>成功した{@link #ship}の回数と{@code shipped}が一致する（更新を落とさない）</li>
 * </ul>
 *
 * <p>メソッドの名前・引数・戻り値は採点の足場が呼ぶので変えないこと。
 * 中身の作りは自由に変えてよい（フィールドを増やす・型を変える・持ち方を変えるのも自由）。
 */
public class StockCounter {

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
        // TODO: 在庫の確認と2つの値の更新を、他のスレッドが割り込めない1つの区間にする
        if (stock < quantity) {
            return false;
        }
        stock -= quantity;
        shipped += quantity;
        return true;
    }

    /**
     * 在庫と出荷済み件数を、<b>同じ時点の値として</b>返す。
     *
     * @return {@code {stock, shipped}} の2要素
     */
    public int[] snapshot() {
        // TODO: 2つの値を別々に読むと、更新の途中の状態（合計が合わない状態）が見える
        return new int[] { stock, shipped };
    }
}
