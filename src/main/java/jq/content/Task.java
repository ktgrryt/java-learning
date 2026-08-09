package jq.content;

import jq.format.JavaSnippetFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 練習問題1問（問題文 + ひな形 + テストケース + ヒント + 模範解答）。
 *
 * 1レッスンに複数問入る。★は「レッスン」ではなく「問題」ごとに付くので、
 * 進捗の単位もこの問題のキー（{@code レッスンID#連番}）になる。
 *
 * @param id       レッスン内での連番（"1" が従来の練習問題、"2" 以降が追加問題）
 * @param kind     "practice"（レッスンの本題） / "drill"（直後の再現） / "applied"（応用）
 * @param cases    表示・隠しを合わせた全テストケース
 * @param sourceChecks 出力だけでは確認できない、指定構文の検査
 */
public record Task(
        String id,
        String kind,
        String task,
        String starterCode,
        List<TestCase> cases,
        List<String> hints,
        String solution,
        List<SourceCheck> sourceChecks) {

    /** 画面に出す種別ラベル。 */
    public String label() {
        return switch (kind) {
            case "drill" -> "ドリル";
            case "applied" -> "応用";
            default -> "練習問題";
        };
    }

    /** ブラウザへ渡す表現。隠しケースの中身は落とし、ヒントは本文を渡さず件数だけにする。 */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("label", label());
        m.put("task", task);
        m.put("starterCode", JavaSnippetFormatter.formatIfCompact(starterCode));

        List<Object> caseList = new ArrayList<>();
        int hiddenCount = 0;
        for (TestCase c : cases) {
            if (c.hidden()) {
                hiddenCount++;
                continue;
            }
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("label", c.label());
            cm.put("stdin", c.stdin());
            cm.put("expected", c.expected());
            caseList.add(cm);
        }
        m.put("visibleCases", caseList);
        m.put("hiddenCaseCount", hiddenCount);
        m.put("totalCaseCount", cases.size());
        m.put("hintCount", hints.size());
        m.put("hasSolution", !solution.isEmpty());
        return m;
    }
}
