package cafe.logging;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class IncidentAnalyzer {
    public record Deployment(Instant time, String version) {
    }

    private final List<LogEntry> entries;

    public IncidentAnalyzer(List<LogEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<LogEntry> timeline(String requestId) {
        return entries.stream()
                .filter(entry -> entry.requestId().equals(requestId))
                .sorted(Comparator.comparing(LogEntry::time)
                        .thenComparingInt(LogEntry::sequence))
                .toList();
    }

    public Optional<LogEntry> firstError(String requestId) {
        return timeline(requestId).stream()
                .filter(entry -> entry.level().equals("ERROR"))
                .findFirst();
    }

    public static Optional<Deployment> latestDeploymentBefore(
            Instant time, List<Deployment> deployments) {
        return deployments.stream()
                .filter(deployment -> !deployment.time().isAfter(time))
                .max(Comparator.comparing(Deployment::time));
    }
}
