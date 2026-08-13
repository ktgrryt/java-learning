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

    private final List<CurriculumPart> parts;
    private final List<Chapter> chapters;
    private final Map<String, Lesson> lessonsById = new LinkedHashMap<>();
    private final Map<String, Chapter> chapterOfLesson = new LinkedHashMap<>();

    Curriculum(List<CurriculumPart> parts) {
        this.parts = List.copyOf(parts);
        List<Chapter> allChapters = new ArrayList<>();
        for (CurriculumPart part : this.parts) {
            allChapters.addAll(part.chapters());
        }
        this.chapters = List.copyOf(allChapters);
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

    public List<CurriculumPart> parts() {
        return parts;
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

    /** 全問題の数。★の分母になる（1レッスンに複数問あるのでレッスン数とは一致しない）。 */
    public int totalTaskCount() {
        int n = 0;
        for (Lesson l : lessonsById.values()) {
            n += (int) l.tasks().stream().filter(Task::required).count();
        }
        return n;
    }

    /** 全レッスンIDを出題順に並べたもの。 */
    public List<String> lessonOrder() {
        return List.copyOf(lessonsById.keySet());
    }

    /** 全問題を出題順に並べたもの。 */
    public List<TaskRef> taskOrder() {
        List<TaskRef> order = new ArrayList<>();
        for (Chapter c : chapters) {
            for (Lesson l : c.lessons()) {
                for (Task t : l.tasks()) {
                    if (t.required()) order.add(new TaskRef(l.id(), t.id()));
                }
            }
        }
        return order;
    }

    public Optional<Task> task(String lessonId, String taskId) {
        return lesson(lessonId).flatMap(l -> l.task(taskId));
    }

    /** 章に含まれる問題の数。 */
    public int taskCount(Chapter chapter) {
        int n = 0;
        for (Lesson l : chapter.lessons()) {
            n += (int) l.tasks().stream().filter(Task::required).count();
        }
        return n;
    }

    /** 章の問題のうち、クリア済みの数。 */
    public int clearedCount(Chapter chapter, Set<String> clearedKeys) {
        int count = 0;
        for (Lesson l : chapter.lessons()) {
            count += clearedCount(l, clearedKeys);
        }
        return count;
    }

    /** レッスンの問題のうち、クリア済みの数。 */
    public int clearedCount(Lesson lesson, Set<String> clearedKeys) {
        int count = 0;
        for (String key : lesson.taskKeys()) {
            if (clearedKeys.contains(key)) {
                count++;
            }
        }
        return count;
    }

    /** レッスンの★（全問クリアで付く）。 */
    public boolean isLessonCleared(Lesson lesson, Set<String> clearedKeys) {
        // 事前確認は採点問題ではなく、環境は後から変わり得る。★の対象にはしない。
        List<String> requiredKeys = lesson.taskKeys();
        return !requiredKeys.isEmpty() && clearedCount(lesson, clearedKeys) == requiredKeys.size();
    }

    public boolean isChapterCleared(Chapter chapter, Set<String> clearedKeys) {
        return clearedCount(chapter, clearedKeys) == taskCount(chapter);
    }

    /**
     * 指定問題の次の問題（同じレッスン内の次の問題 → 次のレッスンの1問目、の順）。
     * 最後の問題なら null。
     */
    public TaskRef nextTask(String lessonId, String taskId) {
        List<TaskRef> order = taskOrder();
        int i = order.indexOf(new TaskRef(lessonId, taskId));
        return (i >= 0 && i + 1 < order.size()) ? order.get(i + 1) : null;
    }

    /** 問題1つを指す参照。 */
    public record TaskRef(String lessonId, String taskId) {
        public String key() {
            return Lesson.taskKey(lessonId, taskId);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lessonId", lessonId);
            m.put("taskId", taskId);
            return m;
        }
    }

    public Object toPublicJson() {
        List<Object> list = new ArrayList<>();
        for (Chapter c : chapters) {
            list.add(c.toPublicJson());
        }
        return list;
    }
}
