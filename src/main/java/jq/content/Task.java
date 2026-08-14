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
 * @param required falseなら採点できる任意発展問題。章クリア・★・カフェ報酬の対象外
 * @param type     "single-file"（Javaコード） / "artifact"（設定などの1ファイル） /
 *                 "project"（既存labの複数ファイル） / "runtime-lab"（実環境を起動するlab）
 * @param cases    表示・隠しを合わせた全テストケース
 * @param sourceChecks 出力だけでは確認できない、指定構文の検査
 * @param artifact artifact問題の対象と検査。single-file問題ではnull
 * @param project project問題の対象と実行方法。それ以外ではnull
 * @param runtimeLab runtime-lab問題の対象、必要環境、実行時検査。それ以外ではnull
 * @param rubric   この問題が測る実務能力（省略可）。空なら型から導く（{@link #rubricDimensions()}）
 */
public record Task(
        String id,
        String kind,
        boolean required,
        String type,
        String task,
        String starterCode,
        List<TestCase> cases,
        List<String> hints,
        String solution,
        List<SourceCheck> sourceChecks,
        ArtifactSpec artifact,
        ProjectSpec project,
        RuntimeLabSpec runtimeLab,
        List<String> rubric) {

    /**
     * 実務rubricの5軸。§8.4の項目に対応する。
     *
     * <p>問題の型（4種類）からは5軸を区別できない。とくに`runtime-lab`は
     * 「実装できる」と「壊れた状態を直せる」の両方を兼ねるので、
     * どちらを測っているかは教材側で示す必要がある。
     */
    public static final List<String> RUBRIC_DIMENSIONS =
            List.of("explain", "implement", "diagnose", "test", "decide");

    /**
     * この問題が測る軸。教材側に{@code rubric}があればそれを使い、無ければ型から導く。
     *
     * <p>導出は控えめにする。`single-file`と`artifact`は「実装できる」だけを主張し、
     * 診断や判断まで測ったことにはしない。それらを主張したい問題には`rubric`を書く。
     */
    public List<String> rubricDimensions() {
        if (!rubric.isEmpty()) {
            return rubric;
        }
        return isMultiFile() ? List.of("implement", "diagnose") : List.of("implement");
    }

    public boolean isArtifact() {
        return "artifact".equals(type);
    }

    public boolean isOptional() {
        return !required;
    }

    public boolean isProject() {
        return "project".equals(type);
    }

    public boolean isRuntimeLab() {
        return "runtime-lab".equals(type);
    }

    public boolean isMultiFile() {
        return isProject() || isRuntimeLab();
    }

    public ProjectSpec workspace() {
        if (isProject()) return project;
        if (isRuntimeLab()) return runtimeLab.workspace();
        throw new IllegalStateException("複数ファイル問題ではありません: " + type);
    }

    public boolean hasSolution() {
        return isProject() ? project.hasSolution()
                : (isRuntimeLab() ? runtimeLab.hasSolution() : !solution.isEmpty());
    }

    /** 画面に出す種別ラベル。 */
    public String label() {
        if (isOptional()) return "任意発展";
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
        m.put("required", required);
        m.put("type", type);
        m.put("label", label());
        m.put("task", task);
        m.put("starterCode", isArtifact() || isMultiFile()
                ? starterCode
                : JavaSnippetFormatter.formatIfCompact(starterCode));
        if (isArtifact()) {
            m.put("artifact", artifact.toPublicJson());
        }
        if (isProject()) {
            m.put("project", project.toPublicJson());
        }
        if (isRuntimeLab()) {
            m.put("runtimeLab", runtimeLab.toPublicJson());
        }

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
        // 進捗の「通過した検証数」は問題形式をまたいで同じ欄に集計する。
        m.put("totalCaseCount", isArtifact() ? artifact.checks().size()
                : (isProject() ? 1 : (isRuntimeLab() ? runtimeLab.checks().size() : cases.size())));
        m.put("hintCount", hints.size());
        m.put("hasSolution", hasSolution());
        return m;
    }
}
