import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.Lesson;
import jq.content.Task;
import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 3層（概念／コード／実践）の数え方と、達成状態の永続化を確かめる。
 *
 * <p>いちばん確かめたいのは「一度達成した層は、章へ問題が増えても記録が消えない」ことである。
 * 導出だけにしていると、章を書き足した瞬間に過去の達成が未達成へ戻ってしまう。
 */
public final class LayerCompletionCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".");
        Curriculum curriculum = new ContentLoader(project.resolve("content")).load();

        checkLayerCounting(curriculum);
        checkPersistence(curriculum);

        if (failures > 0) {
            throw new AssertionError(failures + "件の検査に失敗しました");
        }
        System.out.println("layer completion: すべて合格");
    }

    /** 数え方が問題の種類から導かれ、任意問題を数えないこと。 */
    private static void checkLayerCounting(Curriculum curriculum) {
        Chapter chapter = curriculum.chapters().stream()
                .filter(ch -> hasPractice(ch))
                .findFirst()
                .orElseThrow(() -> new AssertionError("実践層を持つ章がありません"));

        Set<String> nothing = Set.of();
        Curriculum.LayerProgress practice = curriculum.layerProgress(
                chapter, Curriculum.Layer.PRACTICE, nothing, (id, i) -> false);
        check(practice.total() > 0, "実践層の対象数が0です: " + chapter.id());
        check(practice.done() == 0, "何もクリアしていないのに達成数が0ではありません");
        check(!practice.complete(), "何もクリアしていないのに達成になっています");

        // 任意問題（required:false）は分母へ入れない
        long optional = chapter.lessons().stream()
                .flatMap(l -> l.tasks().stream())
                .filter(Task::isOptional)
                .count();
        long required = chapter.lessons().stream()
                .flatMap(l -> l.tasks().stream())
                .filter(t -> !t.isOptional() && t.isMultiFile())
                .count();
        check(practice.total() == required,
                "実践層の対象数が必須問題の数と一致しません: " + practice.total() + " != " + required
                        + "（任意問題 " + optional + "件）");

        // すべてクリアすれば達成になる
        Set<String> allCleared = new HashSet<>();
        for (Lesson lesson : chapter.lessons()) {
            allCleared.addAll(lesson.taskKeys());
        }
        Curriculum.LayerProgress full = curriculum.layerProgress(
                chapter, Curriculum.Layer.PRACTICE, allCleared, (id, i) -> false);
        check(full.complete(), "全問クリアしても実践層が達成になりません: " + chapter.id());

        // 対象0件の層は達成にしない（バッジだけ点くのを防ぐ）
        Chapter noPractice = curriculum.chapters().stream()
                .filter(ch -> !hasPractice(ch))
                .findFirst()
                .orElse(null);
        if (noPractice != null) {
            Curriculum.LayerProgress empty = curriculum.layerProgress(
                    noPractice, Curriculum.Layer.PRACTICE, allCleared, (id, i) -> true);
            check(empty.total() == 0, "実践層を持たない章の対象数が0ではありません");
            check(!empty.complete(), "対象0件の層が達成になっています: " + noPractice.id());
        }
    }

    /** 達成日が保存され、章へ問題が増えても消えないこと。 */
    private static void checkPersistence(Curriculum curriculum) throws Exception {
        Path dir = Files.createTempDirectory("jq-layer-check-");
        Path file = dir.resolve("progress.json");
        try {
            ProgressStore store = new ProgressStore(file);
            check(store.layerCompletedAt("ch01", "concept") == null,
                    "初期状態で達成日が入っています");

            check(store.recordLayerCompletion("ch01", "concept"),
                    "1回目の記録がtrueになりません");
            String first = store.layerCompletedAt("ch01", "concept");
            check(first != null && !first.isBlank(), "達成日が空です");

            check(!store.recordLayerCompletion("ch01", "concept"),
                    "2回目の記録がfalseになりません（達成日を上書きしています）");
            check(first.equals(store.layerCompletedAt("ch01", "concept")),
                    "2回目の記録で達成日が変わりました");

            store.flushNow();
            ProgressStore reloaded = new ProgressStore(file);
            check(first.equals(reloaded.layerCompletedAt("ch01", "concept")),
                    "読み直したときに達成日が失われました");

            // 層ごとに別で管理される
            check(reloaded.layerCompletedAt("ch01", "practice") == null,
                    "別の層の達成日が入っています");
            check(reloaded.layerCompletedAt("ch02", "concept") == null,
                    "別の章の達成日が入っています");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static boolean hasPractice(Chapter chapter) {
        return chapter.lessons().stream()
                .flatMap(l -> l.tasks().stream())
                .anyMatch(t -> !t.isOptional() && t.isMultiFile());
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + message);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
