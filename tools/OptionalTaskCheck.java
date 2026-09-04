import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.Lesson;
import jq.content.Task;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** required:falseが章クリア・★・出題順の分母へ入らないことを回帰検査する。 */
public final class OptionalTaskCheck {
    public static void main(String[] args) {
        Curriculum curriculum = new ContentLoader(Path.of("content")).load();
        Lesson lesson = curriculum.lesson("62-5").orElseThrow();
        Task nativeTask = lesson.tasks().stream()
                .filter(Task::isOptional)
                .filter(Task::isRuntimeLab)
                .findFirst()
                .orElseThrow();
        require(nativeTask.isRuntimeLab(), "任意課題がNative runtime-labではありません");
        require(nativeTask.label().equals("任意発展"), "任意課題の表示ラベルが不正です");
        require(lesson.taskKeys().stream().noneMatch(key -> key.endsWith("#" + nativeTask.id())),
                "任意課題がレッスン必須キーへ混入しています");
        require(curriculum.taskOrder().stream().noneMatch(ref ->
                        ref.lessonId().equals("62-5") && ref.taskId().equals(nativeTask.id())),
                "任意課題が必須出題順へ混入しています");

        Chapter chapter = curriculum.chapterOf("62-5");
        Set<String> requiredCleared = new HashSet<>();
        for (Lesson chapterLesson : chapter.lessons()) requiredCleared.addAll(chapterLesson.taskKeys());
        require(curriculum.isChapterCleared(chapter, requiredCleared),
                "必須問題だけを解いてもQuarkus章をクリアできません");
        require(curriculum.clearedCount(chapter, requiredCleared) == curriculum.taskCount(chapter),
                "任意課題が章の問題数へ混入しています");
        System.out.println("optional task: すべて合格");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
