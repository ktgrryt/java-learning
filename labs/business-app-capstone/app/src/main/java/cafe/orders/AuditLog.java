package cafe.orders;

public interface AuditLog {
    void orderCancelled(String requestId, long orderId, long actorId, String result);
}
