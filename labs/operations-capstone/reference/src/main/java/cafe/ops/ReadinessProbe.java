package cafe.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReadinessProbe {

    private final List<DependencyCheck> checks;

    public ReadinessProbe(List<DependencyCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    public Readiness check() {
        Map<String, String> states = new LinkedHashMap<>();
        boolean ready = true;
        for (DependencyCheck check : checks) {
            boolean up;
            try {
                up = check.up();
            } catch (RuntimeException e) {
                // 落ちている依存先は綺麗にfalseを返さず、接続例外を投げてくる。
                // ここで漏らすとreadinessを答えられず、配備の入れ替えが判断できない。
                up = false;
            }
            states.put(check.name(), up ? "up" : "down");
            if (!up) {
                ready = false;
            }
        }
        return new Readiness(ready, states);
    }
}
