package cafe.orders;

import java.time.Clock;
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
        // TODO: 入力、再送、存在、所有者、状態の順に確認する
        // TODO: 成功時は注文とoutboxを一緒に保存し、安全な監査ログを残す
        return new CancelResult("no_change", null);
    }
}
