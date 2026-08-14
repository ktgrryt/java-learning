package cafe.api;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** 注文の置き場。参照専用（この演習では編集しません）。 */
@ApplicationScoped
public class OrderRepository {

    private final List<Order> orders = new CopyOnWriteArrayList<>(List.of(
            new Order("ORD-1", "エスプレッソ", 2, "aki", "常連。次回クーポン検討"),
            new Order("ORD-2", "ドリップ", 1, "bob", "支払い遅延あり")));

    public List<Order> findAll() {
        return new ArrayList<>(orders);
    }

    public Optional<Order> find(String id) {
        return orders.stream().filter(order -> order.id().equals(id)).findFirst();
    }

    /** 削除できたら true。無ければ false。 */
    public boolean delete(String id) {
        return orders.removeIf(order -> order.id().equals(id));
    }
}
