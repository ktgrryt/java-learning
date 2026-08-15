package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章1つ。レッスンの並び順は、学ぶ順番の目安（順番は強制しない）。
 *
 * <p>{@code objectives} は「この章を終えると何ができるようになるか」で、レッスンと問題の
 * {@code objectiveIds} から参照される。章クリアや {@code layers} が答える「終わったか」とは別の層。
 */
public record Chapter(
        String id,
        String partId,
        int number,
        int partNumber,
        String title,
        String subtitle,
        String emoji,
        List<Objective> objectives,
        List<Lesson> lessons) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("partId", partId);
        m.put("number", number);
        m.put("partNumber", partNumber);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("emoji", emoji);
        List<Object> os = new ArrayList<>();
        for (Objective o : objectives) {
            os.add(o.toPublicJson());
        }
        m.put("objectives", os);
        List<Object> ls = new ArrayList<>();
        for (Lesson l : lessons) {
            ls.add(l.toPublicJson());
        }
        m.put("lessons", ls);
        return m;
    }
}
