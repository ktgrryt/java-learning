package jq.runner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ユーザーコードを1回実行した結果。
 *
 * @param stdout    標準出力（上限で打ち切られることがある）
 * @param stderr    標準エラー出力（例外のスタックトレースなど）
 * @param exitCode  終了コード。タイムアウトで殺した場合は -1
 * @param timedOut  実行時間の上限を超えたか
 * @param truncated 出力が上限を超えて打ち切られたか
 */
public record RunResult(String stdout, String stderr, int exitCode, boolean timedOut, boolean truncated) {

    /** 例外などで異常終了したか。 */
    public boolean crashed() {
        return !timedOut && exitCode != 0;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stdout", stdout);
        m.put("stderr", stderr);
        m.put("exitCode", exitCode);
        m.put("timedOut", timedOut);
        m.put("truncated", truncated);
        return m;
    }
}
