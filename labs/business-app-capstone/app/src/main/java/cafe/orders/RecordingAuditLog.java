package cafe.orders;

import java.util.ArrayList;
import java.util.List;

public final class RecordingAuditLog implements AuditLog {
    public record Entry(String requestId, long orderId, long actorId, String result) {
    }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void orderCancelled(String requestId, long orderId, long actorId, String result) {
        entries.add(new Entry(requestId, orderId, actorId, result));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }
}
