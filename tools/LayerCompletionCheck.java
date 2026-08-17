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
        checkConceptLessons(curriculum);
        checkConceptDeclarations(curriculum);
        checkConceptKeyMigration(curriculum);
        checkPersistence(curriculum);
        checkCafelessSave();

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

    /**
     * 概念レッスンの★が、層の数え方と章クリアの分母の両方でつじつまが合うこと。
     *
     * <p>概念レッスンは提出課題を持たない。★は {@link Lesson#taskKeys()} が返す1件だけなので、
     * 分母を {@code tasks()} で数えている箇所が残っていると、その章は永久に章クリアできない。
     * コード層・実践層へ混ざっていないことも同時に見る（概念はクイズで測る）。
     */
    private static void checkConceptLessons(Curriculum curriculum) {
        // 概念レッスンを持つ章はすべて見る。1章だけ見ていると、概念レッスンの割合が高い章
        // （システム開発ライフサイクル編は5レッスン中2つが概念）の数え方が確かめられない。
        for (Chapter chapter : curriculum.chapters()) {
            if (chapter.lessons().stream().anyMatch(Lesson::concept)) {
                checkConceptLessonsIn(curriculum, chapter);
            }
        }
    }

    private static void checkConceptLessonsIn(Curriculum curriculum, Chapter chapter) {
        long conceptLessons = chapter.lessons().stream().filter(Lesson::concept).count();
        for (Lesson lesson : chapter.lessons()) {
            if (!lesson.concept()) {
                continue;
            }
            check(lesson.tasks().isEmpty(),
                    "概念レッスンが提出課題を持っています: " + lesson.id());
            check(lesson.taskKeys().size() == 1,
                    "概念レッスンの★が1件ではありません: " + lesson.id());
            check(lesson.quizzes().size() >= 3,
                    "概念レッスンのクイズが3問未満です: " + lesson.id());
        }

        // 章クリアの分母（taskCount）に概念レッスンの★が入っていること
        Set<String> allCleared = new HashSet<>();
        for (Lesson lesson : chapter.lessons()) {
            allCleared.addAll(lesson.taskKeys());
        }
        check(curriculum.taskCount(chapter) == allCleared.size(),
                "章クリアの分母が★の数と一致しません: " + chapter.id()
                        + "（" + curriculum.taskCount(chapter) + " != " + allCleared.size() + "）");
        check(curriculum.isChapterCleared(chapter, allCleared),
                "全★をクリアしても章クリアになりません: " + chapter.id());

        // 概念レッスンの★はコード層・実践層の対象にしない（測っているのはクイズ）
        long codingAndPractice = 0;
        for (Curriculum.Layer layer : List.of(Curriculum.Layer.CODING, Curriculum.Layer.PRACTICE)) {
            codingAndPractice += curriculum.layerProgress(
                    chapter, layer, allCleared, (id, i) -> false).total();
        }
        long codeTasks = chapter.lessons().stream()
                .flatMap(l -> l.tasks().stream())
                .filter(t -> !t.isOptional())
                .count();
        check(codingAndPractice == codeTasks,
                "概念レッスンの★がコード層・実践層へ混ざっています: " + chapter.id()
                        + "（" + codingAndPractice + " != " + codeTasks + "）");
        check(curriculum.taskCount(chapter) == codeTasks + conceptLessons,
                "概念レッスンの数だけ分母が増えていません: " + chapter.id());
    }

    /**
     * 概念レッスンへ変えたレッスンの★が、読み直しても残ること。
     *
     * <p>提出課題を持っていたレッスンを概念レッスンへ変えると、★のキーが {@code 50-4#1} から
     * {@code 50-4#q} へ変わる。{@code ProgressStore} の読み替えが外れると、すでにクリアした人の
     * ★が黙って消え、章クリアも外れる。消えても例外は出ないので、機械で見張る。
     */
    private static void checkConceptKeyMigration(Curriculum curriculum) throws Exception {
        // 教材側で概念レッスンになっているものだけを対象にする（変換を戻したら検査も外れる）
        List<String> converted = new java.util.ArrayList<>();
        for (Chapter chapter : curriculum.chapters()) {
            for (Lesson lesson : chapter.lessons()) {
                if (lesson.concept() && migratedLessonIds().contains(lesson.id())) {
                    converted.add(lesson.id());
                }
            }
        }
        if (converted.isEmpty()) {
            return;
        }

        Path dir = Files.createTempDirectory("jq-concept-migrate-");
        Path file = dir.resolve("progress.json");
        try {
            StringBuilder json = new StringBuilder("{\n  \"cleared\": {\n");
            for (int i = 0; i < converted.size(); i++) {
                json.append("    \"").append(converted.get(i)).append("#1\": ")
                        .append("{\"clearedAt\": \"2026-08-01\", \"hintsUsed\": 0, \"attempts\": 1}")
                        .append(i + 1 < converted.size() ? ",\n" : "\n");
            }
            json.append("  }\n}\n");
            Files.writeString(file, json.toString());

            ProgressStore store = new ProgressStore(file);
            Set<String> cleared = store.clearedIds();
            for (String lessonId : converted) {
                check(cleared.contains(lessonId + "#q"),
                        "概念レッスンへ変えた " + lessonId + " の★が読み替えられていません");
                check(!cleared.contains(lessonId + "#1"),
                        "古い問題キーが残っています: " + lessonId + "#1");
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 提出課題を持っていた頃の★を読み替える対象のレッスンID。
     *
     * <p>一覧は {@code ProgressStore} から読む。ここに写しを置くと、変換したレッスンを
     * 読み替えへ足し忘れたときに**検査側も同じ抜け方をして気づけない**（実際に `53-5` で起きた）。
     */
    private static Set<String> migratedLessonIds() {
        Set<String> ids = new HashSet<>();
        for (String key : ProgressStore.conceptMigratedTaskKeys()) {
            ids.add(key.substring(0, key.indexOf('#')));
        }
        return ids;
    }

    /**
     * 提出課題を持ったことがない概念レッスン（最初から概念レッスンとして書いたもの）。
     *
     * <p>読み替えの一覧と合わせて、教材のすべての概念レッスンがどちらか一方に入っていることを
     * {@link #checkConceptDeclarations} が検査する。概念レッスンを1つ足すたびにどちらかへ
     * 書き足す手間はあるが、そのおかげで**変換したのに読み替えを足し忘れた状態が必ず落ちる**。
     */
    private static final Set<String> NEW_CONCEPT_LESSON_IDS = Set.of(
            "70-1", "70-2", "70-3",
            "71-1",
            "72-1", "72-4",
            "73-1", "73-4",
            "74-1", "74-4",
            "75-1", "75-4");

    /**
     * すべての概念レッスンが「変換したもの」「最初から概念のもの」のどちらか一方に入っていること。
     *
     * <p>変換したのに読み替えを足さないと、すでにクリアした人の★が消える。消えても例外は出ず、
     * 読み替えの検査（{@link #checkConceptKeyMigration}）も対象から外れるだけなので通ってしまう。
     * そこで教材側の概念レッスンを起点に、宣言の抜けを落とす。
     */
    private static void checkConceptDeclarations(Curriculum curriculum) {
        Set<String> migrated = migratedLessonIds();
        Set<String> seen = new HashSet<>();
        for (Chapter chapter : curriculum.chapters()) {
            for (Lesson lesson : chapter.lessons()) {
                if (!lesson.concept()) {
                    continue;
                }
                seen.add(lesson.id());
                boolean isMigrated = migrated.contains(lesson.id());
                boolean isNew = NEW_CONCEPT_LESSON_IDS.contains(lesson.id());
                check(isMigrated || isNew,
                        "概念レッスン " + lesson.id() + " がどちらの一覧にも入っていません"
                                + "（提出課題を持っていたなら ProgressStore.CONCEPT_MIGRATED_TASK_KEYS へ "
                                + lesson.id() + "#1 を足し、新しく書いたなら "
                                + "LayerCompletionCheck.NEW_CONCEPT_LESSON_IDS へ足してください）");
                check(!(isMigrated && isNew),
                        "概念レッスン " + lesson.id() + " が両方の一覧に入っています");
            }
        }
        for (String id : migrated) {
            check(seen.contains(id),
                    "読み替えの対象 " + id + " が概念レッスンではありません"
                            + "（通常のレッスンへ戻したなら、読み替えも外してください）");
        }
        for (String id : NEW_CONCEPT_LESSON_IDS) {
            check(seen.contains(id),
                    "最初から概念レッスンの一覧にある " + id + " が概念レッスンではありません");
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

    /**
     * カフェの状態を持たない進捗ファイルでも、達成日が読めること。
     *
     * <p>読み込みは項目ごとに書いてあるので、置き場所を1つ間違えると
     * 「特定の形のセーブだけ消える」という形で表に出る。実際に達成日の読み込みが
     * {@code cafe} の有無を見る分岐の中に入っており、カフェ機能より前のセーブや
     * 手で書いたセーブでは消えていた。上の {@link #checkPersistence} は自分で
     * 書き出したファイル（必ず {@code cafe} を含む）しか読まないので気づけない。
     */
    private static void checkCafelessSave() throws Exception {
        Path dir = Files.createTempDirectory("jq-layer-cafeless-");
        Path file = dir.resolve("progress.json");
        try {
            Files.writeString(file, """
                    {"cleared":{},"layerCompletions":{"ch01#concept":"2026-01-02"}}
                    """);
            ProgressStore store = new ProgressStore(file);
            check("2026-01-02".equals(store.layerCompletedAt("ch01", "concept")),
                    "cafeを持たない進捗ファイルから達成日を読めません: "
                            + store.layerCompletedAt("ch01", "concept"));
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
