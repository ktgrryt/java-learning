package cafe.orders;

import jakarta.data.repository.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Jakarta Data のリポジトリを宣言します。
 *
 * <p>Jakarta Data では、**実装を書きません**。インタフェースの宣言から、
 * ビルド時に実装が生成されます。だから宣言の形そのものが仕様であり、
 * 間違っていれば動きません。
 *
 * <p>この演習で作るもの（4つ）。
 *
 * <ol>
 *   <li>基本操作（保存・削除・全件）は{@code CrudRepository<Order, Long>}を継承して受け取る。</li>
 *   <li>{@code @Find}で、状態を指定して注文を取り出す。引数がどの属性に当たるかは
 *       {@code @By}で示す。並びは{@code @OrderBy}で注文IDの降順にする。</li>
 *   <li>{@code @Query}で、金額のしきい値より大きい注文の件数を数える。
 *       問い合わせは JDQL（Jakarta Data Query Language）で書く。</li>
 *   <li>1件だけ取り出すメソッドは、戻り値を{@code Optional<Order>}にする。
 *       見つからないことを型で表す。</li>
 * </ol>
 *
 * <p>属性名は{@code Order}の項目名（{@code id}・{@code customerId}・{@code status}・{@code amount}）です。
 */
// TODO: @Repository を付け、CrudRepository<Order, Long> を継承する
public interface OrderRepository {

    // TODO: @Find と @By で状態を指定して取り出す。@OrderBy で id の降順にする
    //       戻り値は List<Order>

    // TODO: @Query で amount がしきい値より大きい注文の件数を数える（戻り値は long）

    // TODO: 主キーで1件取り出す。戻り値は Optional<Order>
}
