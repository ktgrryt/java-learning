package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章1つ。レッスンの並び順は、学ぶ順番の目安（順番は強制しない）。
 */
public record Chapter(
        String id,
        int number,
        String title,
        String subtitle,
        String emoji,
        List<Lesson> lessons) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("number", number);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("emoji", emoji);
        List<Object> ls = new ArrayList<>();
        for (Lesson l : lessons) {
            ls.add(l.toPublicJson());
        }
        m.put("lessons", ls);
        return m;
    }
}
