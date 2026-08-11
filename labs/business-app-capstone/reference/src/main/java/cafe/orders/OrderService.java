package cafe.orders;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class OrderService {
    private final OrderRepository repository;
    private final AuditLog auditLog;
    private final Clock clock;
    private final Map<String, CancelResult> processed = new HashMap<>();

    public OrderService(OrderRepository repository, AuditLog auditLog, Clock clock) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public CancelResult cancel(long orderId, CancelOrderRequest request) {
        request.validate();

        CancelResult previous = processed.get(request.idempotencyKey());
        if (previous != null) {
            return previous;
        }

        Order order = repository.findById(orderId).orElseThrow(OrderNotFoundException::new);
        if (order.ownerId() != request.actorId()) {
            throw new OrderForbiddenException();
        }
        if (order.status() == OrderStatus.PAID) {
            throw new OrderConflictException();
        }
        if (order.status() == OrderStatus.CANCELLED) {
            CancelResult result = new CancelResult("no_change", order);
            processed.put(request.idempotencyKey(), result);
            return result;
        }

        Instant now = clock.instant();
        Order updated = order.cancelled(request.normalizedReason(), now);
        OutboxEvent event = new OutboxEvent(
                request.idempotencyKey(), orderId, "OrderCancelled", now);

        repository.saveCancellation(updated, event);

        CancelResult result = new CancelResult("cancelled", updated);
        processed.put(request.idempotencyKey(), result);
        auditLog.orderCancelled(
                request.requestId(), orderId, request.actorId(), result.result());
        return result;
    }
}
