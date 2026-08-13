package jq.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** 外部ツールを使う章へ入る前に実測する、安全な確認項目。 */
public record PreflightCheck(
        String id,
        String type,
        String label,
        boolean required,
        String tool,
        String minimumVersion,
        int port,
        String help) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("type", type);
        json.put("label", label);
        json.put("required", required);
        json.put("minimumVersion", minimumVersion);
        if (port > 0) json.put("port", port);
        json.put("help", help);
        return json;
    }
}
