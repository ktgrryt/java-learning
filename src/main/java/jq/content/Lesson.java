package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * レッスン1つ（解説 + サンプル + 練習問題1問）。
 */
public record Lesson(
        String id,
        String chapterId,
        String title,
        String explanation,
        List<Sample> samples,
        String task,
        String starterCode,
        List<TestCase> cases,
        List<String> hints,
        String solution) {

    /** 隠しテストを含む全ケース。判定時に使う。 */
    public List<TestCase> allCases() {
        return cases;
    }

    /** ブラウザへ渡す表現。隠しケースの中身は落とし、ヒントは本文を渡さず件数だけにする。 */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("chapterId", chapterId);
        m.put("title", title);
        m.put("explanation", explanation);

        List<Object> sampleList = new ArrayList<>();
        for (Sample s : samples) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("caption", s.caption());
            sm.put("code", s.code());
            sm.put("stdin", s.stdin());
            sampleList.add(sm);
        }
        m.put("samples", sampleList);

        m.put("task", task);
        m.put("starterCode", starterCode);

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
