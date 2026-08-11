package cafe.orders;

import java.time.Instant;

public record Order(
        long id,
        long ownerId,
        OrderStatus status,
        String cancelReason,
        Instant cancelledAt,
        long version) {

    public static Order newOrder(long id, long ownerId) {
        return new Order(id, ownerId, OrderStatus.NEW, null, null, 1);
    }

    public Order cancelled(String reason, Instant at) {
        return new Order(id, ownerId, OrderStatus.CANCELLED, reason, at, version + 1);
    }
}
