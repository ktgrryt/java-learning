package cafe.orders;

import java.time.Instant;

public record OutboxEvent(
        String eventId,
        long orderId,
        String eventType,
        Instant createdAt) {
}
