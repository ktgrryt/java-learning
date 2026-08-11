package cafe.logging;

import java.time.Instant;
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
        // TODO: 同じrequestIdだけをtime、sequenceの順に並べる
        return List.of();
    }

    public Optional<LogEntry> firstError(String requestId) {
        // TODO: 時系列で最初のERRORを返す
        return Optional.empty();
    }

    public static Optional<Deployment> latestDeploymentBefore(
            Instant time, List<Deployment> deployments) {
        // TODO: time以下で最も新しい配備を返す
        return Optional.empty();
    }
}
