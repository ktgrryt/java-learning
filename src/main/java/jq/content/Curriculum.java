package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全章をまとめて保持し、IDの解決とクリア判定を担う。
 *
 * どのレッスンにもいつでも飛べる（順番のロックはかけていない）。
 * 学ぶ順番の目安として並び順は保つが、強制はしない。
 */
public final class Curriculum {

    private final List<Chapter> chapters;
    private final Map<String, Lesson> lessonsById = new LinkedHashMap<>();
    private final Map<String, Chapter> chapterOfLesson = new LinkedHashMap<>();

    Curriculum(List<Chapter> chapters) {
        this.chapters = List.copyOf(chapters);
        for (Chapter c : this.chapters) {
            for (Lesson l : c.lessons()) {
                if (lessonsById.put(l.id(), l) != null) {
                    throw new IllegalStateException("レッスンIDが重複しています: " + l.id());
                }
                chapterOfLesson.put(l.id(), c);
            }
        }
    }

    public List<Chapter> chapters() {
        return chapters;
    }

    public Optional<Lesson> lesson(String id) {
        return Optional.ofNullable(lessonsById.get(id));
    }

    public Chapter chapterOf(String lessonId) {
        return chapterOfLesson.get(lessonId);
    }

    public int totalLessonCount() {
        return lessonsById.size();
    }

    /** 全レッスンIDを出題順に並べたもの。 */
    public List<String> lessonOrder() {
        return List.copyOf(lessonsById.keySet());
    }

    /** 章のレッスンのうち、クリア済みの数。 */
    public int clearedCount(Chapter chapter, Set<String> clearedIds) {
        int count = 0;
        for (Lesson l : chapter.lessons()) {
            if (clearedIds.contains(l.id())) {
                count++;
            }
        }
        return count;
    }

    public boolean isChapterCleared(Chapter chapter, Set<String> clearedIds) {
        return clearedCount(chapter, clearedIds) == chapter.lessons().size();
    }

    /** 指定レッスンの次のレッスンID（章をまたぐ）。無ければ null。 */
    public String nextLessonId(String lessonId) {
        List<String> order = lessonOrder();
        int i = order.indexOf(lessonId);
        return (i >= 0 && i + 1 < order.size()) ? order.get(i + 1) : null;
    }

    public Object toPublicJson() {
        List<Object> list = new ArrayList<>();
        for (Chapter c : chapters) {
            list.add(c.toPublicJson());
        }
        return list;
    }
}
