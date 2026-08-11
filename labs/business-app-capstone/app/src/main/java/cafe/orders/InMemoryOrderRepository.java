package cafe.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InMemoryOrderRepository implements OrderRepository {
    private Order order;
    private final boolean failCommit;
    private final List<OutboxEvent> outbox = new ArrayList<>();
    private int saveCount;

    public InMemoryOrderRepository(Order order) {
        this(order, false);
    }

    public InMemoryOrderRepository(Order order, boolean failCommit) {
        this.order = order;
        this.failCommit = failCommit;
    }

    @Override
    public Optional<Order> findById(long id) {
        return order != null && order.id() == id ? Optional.of(order) : Optional.empty();
    }

    @Override
    public void saveCancellation(Order updated, OutboxEvent event) {
        if (failCommit) {
            throw new IllegalStateException("db-password=secret");
        }
        order = updated;
        outbox.add(event);
        saveCount++;
    }

    public Optional<Order> current() {
        return Optional.ofNullable(order);
    }

    public List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }

    public int saveCount() {
        return saveCount;
    }
}
