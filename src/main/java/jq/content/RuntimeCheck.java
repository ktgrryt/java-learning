package jq.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** runtime-labの固定scriptが報告する、実環境での確認項目。 */
public record RuntimeCheck(String id, String label) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("label", label);
        return json;
    }
}
