package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 複数の章をまとめる大区分（「Java SE編」「Jakarta EE編」など）。
 * 章番号や進捗キーには影響せず、カリキュラムを見つけやすくするためだけに使う。
 */
public record CurriculumPart(
        String id,
        String title,
        String subtitle,
        String emoji,
        List<Chapter> chapters) {

    /** 章本体はstateのchaptersにあるため、ここでは対応するIDだけを公開する。 */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("emoji", emoji);
        List<String> chapterIds = new ArrayList<>();
        for (Chapter chapter : chapters) {
            chapterIds.add(chapter.id());
        }
        m.put("chapterIds", chapterIds);
        return m;
    }
}
