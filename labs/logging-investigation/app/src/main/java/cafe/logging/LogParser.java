package cafe.logging;

import java.util.List;

public final class LogParser {
    private LogParser() {
    }

    public static LogEntry parseLine(String line, int sequence) {
        // TODO: time|level|service|requestId|event|key=value ... を解析する
        throw new UnsupportedOperationException("TODO");
    }

    public static List<LogEntry> parse(List<String> lines) {
        // TODO: 入力順をsequenceとして保持する
        return List.of();
    }
}
