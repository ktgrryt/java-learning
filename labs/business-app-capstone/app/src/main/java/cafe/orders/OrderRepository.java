package cafe.orders;

import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(long id);

    void saveCancellation(Order order, OutboxEvent event);
}
