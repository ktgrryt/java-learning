package jq.runner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * コンパイルエラー/警告1件。
 *
 * @param kind     "error" か "warning"
 * @param line     ソースの行番号（不明なら 0）
 * @param column   列番号（不明なら 0）
 * @param message  javac が出した元のメッセージ
 * @param hint     初心者向けの日本語ヒント（無ければ空文字）
 */
public record Diagnostic(String kind, int line, int column, String message, String hint) {

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("line", line);
        m.put("column", column);
        m.put("message", message);
        m.put("hint", hint);
        return m;
    }
}
