package jq.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** project問題で表示する1ファイル。solutionはサーバー内だけに保持する。 */
public record ProjectFile(
        String path,
        String language,
        String starterContent,
        boolean editable,
        String solutionContent) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path);
        json.put("language", language);
        json.put("content", starterContent);
        json.put("editable", editable);
        return json;
    }
}
