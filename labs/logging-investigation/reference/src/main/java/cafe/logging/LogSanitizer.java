package cafe.logging;

import java.util.List;
import java.util.StringJoiner;

public final class LogSanitizer {
    private static final List<String> SAFE_FIELDS =
            List.of("orderId", "result", "durationMs", "version");

    private LogSanitizer() {
    }

    public static String render(LogEntry entry) {
        StringJoiner line = new StringJoiner(" ");
        line.add("time=" + entry.time());
        line.add("level=" + entry.level());
        line.add("service=" + entry.service());
        line.add("requestId=" + entry.requestId());
        line.add("event=" + entry.event());
        for (String key : SAFE_FIELDS) {
            if (entry.fields().containsKey(key)) {
                line.add(key + "=" + entry.fields().get(key));
            }
        }
        return line.toString();
    }
}
