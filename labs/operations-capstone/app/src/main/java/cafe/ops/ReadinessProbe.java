package cafe.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * readiness（受け入れ可能か）を返す。
 *
 * <p>いま起きていること: 自分のプロセスが起きていれば常に {@code ready=true} を返している。
 * 在庫DBが落ちていても受け入れ可能と答えるため、配備の入れ替えが「成功」と判定され、
 * 落ちた依存先へ要求を流し続けてしまう。
 *
 * <p>直すときの約束。
 *
 * <ul>
 *   <li>依存先ごとに {@code "up"} / {@code "down"} を{@link Readiness#dependencies()}へ入れる。</li>
 *   <li>1つでも落ちていれば {@code ready=false} にする。</li>
 *   <li>依存先の確認が例外を投げても、この確認自体は例外を投げない
 *       （落ちている依存先は綺麗にfalseを返さず、接続例外を投げてくる）。</li>
 * </ul>
 */
public final class ReadinessProbe {

    private final List<DependencyCheck> checks;

    public ReadinessProbe(List<DependencyCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    public Readiness check() {
        Map<String, String> states = new LinkedHashMap<>();
        // TODO: 依存先を1つずつ確認し、名前ごとに "up" / "down" を入れる。
        //       例外は捕まえて "down" にする。1つでも "down" があれば ready=false。
        return new Readiness(true, states);
    }
}
