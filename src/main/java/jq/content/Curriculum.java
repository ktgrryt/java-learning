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

    /**
     * 全問題の数。★の分母になる（1レッスンに複数問あるのでレッスン数とは一致しない）。
     *
     * 数えるのは {@link Lesson#taskKeys()}、つまり★が付くキーである。{@code tasks()} を
     * 直接数えると、問題を持たない概念レッスンの★だけが分母から漏れて章クリアが成立しなくなる。
     */
    public int totalTaskCount() {
        int n = 0;
        for (Lesson l : lessonsById.values()) {
            n += l.taskKeys().size();
        }
        return n;
    }

    /** 全レッスンIDを出題順に並べたもの。 */
    public List<String> lessonOrder() {
        return List.copyOf(lessonsById.keySet());
    }

    /**
     * 全問題を出題順に並べたもの。
     *
     * 概念レッスンは問題を持たないが、★のキーを1つ持つので同じ並びへ入れる。
     * 入れないと「次へ」が概念レッスンを飛ばして、読まないまま先へ進んでしまう。
     */
    public List<TaskRef> taskOrder() {
        List<TaskRef> order = new ArrayList<>();
        for (Chapter c : chapters) {
            for (Lesson l : c.lessons()) {
                if (l.concept()) {
                    order.add(new TaskRef(l.id(), Lesson.CONCEPT_TASK_ID));
                    continue;
                }
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

    /** 章に含まれる★の数（問題と、概念レッスン1つあたり1）。{@link #clearedCount} の分母。 */
    public int taskCount(Chapter chapter) {
        int n = 0;
        for (Lesson l : chapter.lessons()) {
            n += l.taskKeys().size();
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

    /** 修了条件の3層。数え方は問題の種類から導く（教材側に項目を足さない）。 */
    public enum Layer {
        /** 概念。クイズで確かめる。 */
        CONCEPT,
        /** コード。`single-file`と`artifact`で確かめる。 */
        CODING,
        /** 実践。`project`と`runtime-lab`で確かめる。 */
        PRACTICE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 章の1つの層について「対象数」と「達成数」を数える。
     *
     * <p>層はブラウザ側でも数えていたが、達成状態を保存するにはサーバー側の値が要る。
     * 数え方が2箇所にあるとずれるので、ここを唯一の定義にする。
     *
     * @param answeredCorrectly クイズ1問が正解済みかを返す判定（レッスンIDと問番号）
     */
    public LayerProgress layerProgress(Chapter chapter, Layer layer, Set<String> clearedKeys,
                                       java.util.function.BiPredicate<String, Integer> answeredCorrectly) {
        int total = 0;
        int done = 0;
        for (Lesson lesson : chapter.lessons()) {
            if (layer == Layer.CONCEPT) {
                for (int i = 0; i < lesson.quizzes().size(); i++) {
                    total++;
                    if (answeredCorrectly.test(lesson.id(), i)) {
                        done++;
                    }
                }
                continue;
            }
            for (Task task : lesson.tasks()) {
                if (task.isOptional() || layerOf(task) != layer) {
                    continue;
                }
                total++;
                if (clearedKeys.contains(Lesson.taskKey(lesson.id(), task.id()))) {
                    done++;
                }
            }
        }
        return new LayerProgress(total, done);
    }

    /**
     * 章の実務rubric（§8.4）を、実際に解いた問題から算出する。
     *
     * <p>軸ごとに0〜2点。<b>対象が無い軸は「—」（{@code total=0}）</b>とし、0点とは区別する。
     * その章で測る手段が無いことと、測ったが達成していないことは別である。
     *
     * <ul>
     *   <li>2点 … その軸を測る問題（クイズ）を全部クリアした</li>
     *   <li>1点 … 半分以上クリアした</li>
     *   <li>0点 … それ未満</li>
     * </ul>
     *
     * <p>{@code explain}はクイズと、説明を成果物として書かせる問題が対象になる。
     * 文章の質は測れないので、ここで言えるのは「説明を書く課題を通した」までである。
     */
    public RubricScore rubricScore(Chapter chapter, String dimension, Set<String> clearedKeys,
                                   java.util.function.BiPredicate<String, Integer> answeredCorrectly) {
        int total = 0;
        int done = 0;
        for (Lesson lesson : chapter.lessons()) {
            if (dimension.equals("explain")) {
                for (int i = 0; i < lesson.quizzes().size(); i++) {
                    total++;
                    if (answeredCorrectly.test(lesson.id(), i)) {
                        done++;
                    }
                }
            }
            for (Task task : lesson.tasks()) {
                if (task.isOptional() || !task.rubricDimensions().contains(dimension)) {
                    continue;
                }
                total++;
                if (clearedKeys.contains(Lesson.taskKey(lesson.id(), task.id()))) {
                    done++;
                }
            }
        }
        return new RubricScore(total, done);
    }

    /**
     * レッスン1つのrubric。章と同じ数え方を、レッスンの範囲へ当てる。
     *
     * <p>章の合計だけでは「どのレッスンで診断を学んだか」が分からない。
     * レッスンごとに出すことで、章のどこがどの能力に当たるかが読める。
     */
    public RubricScore lessonRubricScore(Lesson lesson, String dimension, Set<String> clearedKeys,
                                         java.util.function.BiPredicate<String, Integer> answeredCorrectly) {
        int total = 0;
        int done = 0;
        if (dimension.equals("explain")) {
            for (int i = 0; i < lesson.quizzes().size(); i++) {
                total++;
                if (answeredCorrectly.test(lesson.id(), i)) {
                    done++;
                }
            }
        }
        for (Task task : lesson.tasks()) {
            if (task.isOptional() || !task.rubricDimensions().contains(dimension)) {
                continue;
            }
            total++;
            if (clearedKeys.contains(Lesson.taskKey(lesson.id(), task.id()))) {
                done++;
            }
        }
        return new RubricScore(total, done);
    }

    /**
     * rubricの1軸の点数。
     *
     * @param total 対象数。0なら「この章では測っていない」
     * @param done  クリア数
     */
    public record RubricScore(int total, int done) {

        public int points() {
            if (total == 0) {
                return 0;
            }
            if (done == total) {
                return 2;
            }
            return done * 2 >= total ? 1 : 0;
        }

        public boolean measured() {
            return total > 0;
        }
    }

    /**
     * 実務修了の条件（§8.4）を満たすか。
     *
     * <p>合計8点以上、かつ「実装」「診断」が各1点以上。ただし<b>その章で測っていない軸は
     * 条件に数えない</b>。クイズしか無い章に診断を要求しても意味がないためである。
     * 測っている軸だけを分母にして、8/10と同じ割合（80%）を満たすかで判定する。
     */
    public boolean meetsRubricThreshold(Chapter chapter, Set<String> clearedKeys,
                                        java.util.function.BiPredicate<String, Integer> answeredCorrectly) {
        int earned = 0;
        int available = 0;
        for (String dimension : Task.RUBRIC_DIMENSIONS) {
            RubricScore score = rubricScore(chapter, dimension, clearedKeys, answeredCorrectly);
            if (!score.measured()) {
                continue;
            }
            available += 2;
            earned += score.points();
            if ((dimension.equals("implement") || dimension.equals("diagnose"))
                    && score.points() < 1) {
                return false;
            }
        }
        return available > 0 && earned * 10 >= available * 8;
    }

    /** 問題が属する層。事前確認は問題ではないので`tasks()`へ入っていない。 */
    public static Layer layerOf(Task task) {
        return task.isMultiFile() ? Layer.PRACTICE : Layer.CODING;
    }

    /**
     * 層の進み具合。
     *
     * <p>対象が0件のときは「達成」にしない。クイズや実践課題を持たない章で
     * バッジだけ点くのは、達成の意味が薄れる。
     */
    public record LayerProgress(int total, int done) {

        public boolean complete() {
            return total > 0 && done == total;
        }
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
