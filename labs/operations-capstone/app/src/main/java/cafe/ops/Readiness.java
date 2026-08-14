package cafe.ops;

import java.util.Map;

/**
 * readinessの答え。
 *
 * @param ready        受け入れ可能か。1つでも落ちていればfalse
 * @param dependencies 依存先の名前 → {@code "up"} または {@code "down"}
 */
public record Readiness(boolean ready, Map<String, String> dependencies) {

    public Readiness(boolean ready, Map<String, String> dependencies) {
        this.ready = ready;
        this.dependencies = Map.copyOf(dependencies);
    }
}
