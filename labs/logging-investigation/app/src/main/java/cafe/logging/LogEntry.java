package cafe.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record LogEntry(
        Instant time,
        String level,
        String service,
        String requestId,
        String event,
        Map<String, String> fields,
        int sequence) {

    public LogEntry {
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }
}
