package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 編集対象となる、Javaソース以外の1ファイル。 */
public record ArtifactSpec(
        String path,
        String format,
        List<ArtifactCheck> checks) {

    /** 解答や検査式そのものを漏らさず、画面に必要な情報だけを返す。 */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path);
        json.put("format", format);
        json.put("checkCount", checks.size());
        List<String> requirements = new ArrayList<>();
        for (ArtifactCheck check : checks) {
            requirements.add(check.message());
        }
        json.put("requirements", requirements);
        return json;
    }
}
