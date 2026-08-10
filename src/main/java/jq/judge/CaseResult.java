package jq.judge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * テストケース1件の判定結果。
 *
 * @param label     表示名
 * @param hidden    隠しケースだったか（UIでバッジを出すために残す）
 * @param pass      合格したか
 * @param stdin     与えた標準入力
 * @param expected  期待した出力
 * @param actual    実際の出力
 * @param diff      不一致の行（合格時は空リスト）
 * @param diffTruncated 差分が長すぎて途中で打ち切ったか
 * @param stderr    実行時エラーの内容（無ければ空文字）
 * @param hint      実行時エラーへの日本語ヒント（無ければ空文字）
 * @param timedOut  実行時間の上限を超えたか
 */
public record CaseResult(
        String label,
        boolean hidden,
        boolean pass,
        String stdin,
        String expected,
        String actual,
        List<DiffLine> diff,
        boolean diffTruncated,
        String stderr,
        String hint,
        boolean timedOut) {

    /**
     * 出力の食い違いを行単位で表したもの。
     *
     * @param lineNo   1始まりの行番号
     * @param expected 期待した行（無い場合は null）
     * @param actual   実際の行（無い場合は null）
     * @param same     一致している行か
     */
    public record DiffLine(int lineNo, String expected, String actual, boolean same) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", lineNo);
            m.put("expected", expected);
            m.put("actual", actual);
            m.put("same", same);
            return m;
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("hidden", hidden);
        m.put("pass", pass);
        m.put("stdin", stdin);
        m.put("expected", expected);
        m.put("actual", actual);
        List<Object> diffJson = diff.stream().map(DiffLine::toJson).map(Object.class::cast).toList();
        m.put("diff", diffJson);
        m.put("diffTruncated", diffTruncated);
        m.put("stderr", stderr);
        m.put("hint", hint);
        m.put("timedOut", timedOut);
        return m;
    }
}
