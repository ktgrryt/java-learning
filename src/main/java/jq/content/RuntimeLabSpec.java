package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * server・DB・HTTP・JFR・containerを実際に動かして確認する問題。
 *
 * workspaceはproject問題と同じ隔離された複数ファイル編集領域を使う。capabilities、
 * requiredTools、requiredImages、checksは教材の目的と実行条件を画面へ明示する。
 */
public record RuntimeLabSpec(
        ProjectSpec workspace,
        List<String> capabilities,
        List<String> requiredTools,
        List<String> requiredImages,
        List<RuntimeCheck> checks) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>(workspace.toPublicJson());
        json.put("capabilities", capabilities);
        json.put("requiredTools", requiredTools);
        json.put("requiredImages", requiredImages);
        List<Object> publicChecks = new ArrayList<>();
        for (RuntimeCheck check : checks) publicChecks.add(check.toPublicJson());
        json.put("checks", publicChecks);
        json.put("checkCount", checks.size());
        return json;
    }

    public boolean hasSolution() {
        return workspace.hasSolution();
    }

    public Map<String, Object> solutionFilesJson() {
        return workspace.solutionFilesJson();
    }
}
