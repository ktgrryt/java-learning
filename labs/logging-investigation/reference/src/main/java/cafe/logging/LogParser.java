package cafe.logging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LogParser {
    private LogParser() {
    }

    public static LogEntry parseLine(String line, int sequence) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("ログは6区画必要です: " + line);
        }
        for (int i = 0; i < 5; i++) {
            if (parts[i].isBlank()) {
                throw new IllegalArgumentException("基本項目は空にできません: " + line);
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (!parts[5].isBlank()) {
            for (String field : parts[5].strip().split("\\s+")) {
                String[] pair = field.split("=", 2);
                if (pair.length != 2 || pair[0].isBlank()) {
                    throw new IllegalArgumentException("key=valueではありません: " + field);
                }
                fields.put(pair[0], pair[1]);
            }
        }

        return new LogEntry(
                Instant.parse(parts[0]),
                parts[1],
                parts[2],
                parts[3],
                parts[4],
                fields,
                sequence);
    }

    public static List<LogEntry> parse(List<String> lines) {
        List<LogEntry> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            result.add(parseLine(lines.get(i), i));
        }
        return List.copyOf(result);
    }
}
