package cafe.orders;

import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.Optional;

/**
 * 実装は書かない。宣言からビルド時に生成される。
 */
@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {

    /** 状態を指定して取り出す。並びは注文IDの降順。 */
    @Find
    @OrderBy(value = "id", descending = true)
    List<Order> byStatus(@By("status") String status);

    /** しきい値より大きい注文の件数。JDQLで書く。 */
    @Query("SELECT COUNT(THIS) WHERE amount > :threshold")
    long countAbove(int threshold);

    /** 主キーで1件。見つからないことを型で表す。 */
    @Find
    Optional<Order> byId(@By("id") Long id);
}
