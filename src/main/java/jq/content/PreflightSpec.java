package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ★を付けずに、ローカル開発環境の準備状態だけを確認するレッスン。 */
public record PreflightSpec(String buttonLabel, List<PreflightCheck> checks) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("buttonLabel", buttonLabel);
        List<Object> publicChecks = new ArrayList<>();
        for (PreflightCheck check : checks) publicChecks.add(check.toPublicJson());
        json.put("checks", publicChecks);
        json.put("requiredCount", checks.stream().filter(PreflightCheck::required).count());
        json.put("optionalCount", checks.stream().filter(check -> !check.required()).count());
        return json;
    }
}
