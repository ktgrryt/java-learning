import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.Lesson;
import jq.content.Task;
import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全教材を順番に初回クリアし、買える経営要素を安い順に購入する経済回帰テスト。
 * 本番と同じ ProgressStore の計算を使うため、設備追加後の終盤詰まりやコイン余りを確認できる。
 */
public final class CafeBalanceSimulation {

    private static final Set<Integer> MILESTONES = Set.of(
            1, 20, 50, 100, 170, 240, 310, 370, 420, 460, 475, 482, 485, 489);

    private record Candidate(String kind, String id, long cost) {
    }

    private CafeBalanceSimulation() {
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path tempDir = Files.createTempDirectory("java-cafe-balance-");
        Path progressFile = tempDir.resolve("progress.json");
        try {
            Curriculum curriculum = new ContentLoader(project.resolve("content")).load();
            ProgressStore progress = new ProgressStore(progressFile);

            System.out.println("star\tchapters\tcash\tlifetime\tnextTask\tstores\tupgrades\tauto\titems");
            for (Chapter chapter : curriculum.chapters()) {
                for (Lesson lesson : chapter.lessons()) {
                    for (Task task : lesson.tasks()) {
                        progress.markCleared(Lesson.taskKey(lesson.id(), task.id()));
                        ProgressStore.CafeLearningProgress learning = learning(curriculum, progress);
                        progress.rewardTask(learning);
                        if (curriculum.isChapterCleared(chapter, progress.clearedIds())) {
                            progress.rewardChapter(chapter.id(), learning, curriculum.taskCount(chapter));
                        }
                        buyAllAffordable(progress, learning);
                        if (MILESTONES.contains(progress.clearedIds().size())) {
                            printRow(progress, learning);
                        }
                    }
                    for (int i = 0; i < lesson.quizzes().size(); i++) {
                        ProgressStore.CafeLearningProgress learning = learning(curriculum, progress);
                        progress.rewardQuiz(lesson.id(), i, learning);
                        buyAllAffordable(progress, learning);
                    }
                }
            }

            ProgressStore.CafeLearningProgress finalLearning = learning(curriculum, progress);
            Map<String, Object> cafe = cafe(progress, finalLearning);
            long lifetime = number(cafe.get("lifetimeCash"));
            long cash = number(cafe.get("cash"));
            double spendPercent = lifetime == 0 ? 0 : (lifetime - cash) * 100.0 / lifetime;
            System.out.printf("FINAL spend=%,d (%.2f%%) cash=%,d lifetime=%,d%n",
                    lifetime - cash, spendPercent, cash, lifetime);
            verifyFinalBalance(curriculum, progress, cafe, spendPercent);
            System.out.println("BALANCE OK: 全コンテンツ購入可・投資率・学習優位の上限を確認しました");
        } finally {
            Files.deleteIfExists(progressFile);
            Files.deleteIfExists(tempDir);
        }
    }

    private static ProgressStore.CafeLearningProgress learning(
            Curriculum curriculum, ProgressStore progress) {
        Set<String> cleared = progress.clearedIds();
        int chapters = 0;
        int masteredTasks = 0;
        for (Chapter chapter : curriculum.chapters()) {
            if (curriculum.isChapterCleared(chapter, cleared)) {
                chapters++;
                masteredTasks += curriculum.taskCount(chapter);
            }
        }
        return new ProgressStore.CafeLearningProgress(chapters, masteredTasks);
    }

    private static void buyAllAffordable(
            ProgressStore progress, ProgressStore.CafeLearningProgress learning) {
        for (int guard = 0; guard < 200; guard++) {
            Map<String, Object> cafe = cafe(progress, learning);
            long cash = number(cafe.get("cash"));
            List<Candidate> candidates = new ArrayList<>();
            collectPurchases(candidates, list(cafe.get("upgrades")), "upgrade", cash);
            collectPurchases(candidates, list(cafe.get("automation")), "automation", cash);
            for (Map<String, Object> item : list(cafe.get("items"))) {
                if (!bool(item.get("owned")) && number(item.get("cost")) <= cash) {
                    candidates.add(new Candidate("item", String.valueOf(item.get("id")),
                            number(item.get("cost"))));
                }
            }
            if (cafe.get("expansionCost") instanceof Number n && n.longValue() <= cash) {
                candidates.add(new Candidate("expansion", "", n.longValue()));
            }
            Candidate next = candidates.stream().min(Comparator.comparingLong(Candidate::cost)).orElse(null);
            if (next == null) {
                return;
            }
            switch (next.kind()) {
                case "upgrade" -> progress.purchaseCafeUpgrade(next.id());
                case "automation" -> progress.purchaseCafeAutomation(next.id());
                case "item" -> progress.purchaseCafeItem(next.id());
                case "expansion" -> progress.expandCafeNetwork();
                default -> throw new IllegalStateException(next.kind());
            }
        }
        throw new IllegalStateException("購入ループが収束しません");
    }

    private static void collectPurchases(
            List<Candidate> result, List<Map<String, Object>> entries, String kind, long cash) {
        for (Map<String, Object> entry : entries) {
            long cost = number(entry.get("cost"));
            if (bool(entry.get("available")) && bool(entry.get("starReady"))
                    && !bool(entry.get("owned")) && cost <= cash) {
                result.add(new Candidate(kind, String.valueOf(entry.get("id")), cost));
            }
        }
    }

    private static void printRow(
            ProgressStore progress, ProgressStore.CafeLearningProgress learning) {
        Map<String, Object> cafe = cafe(progress, learning);
        System.out.printf("%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                progress.clearedIds().size(), learning.clearedChapters(),
                number(cafe.get("cash")), number(cafe.get("lifetimeCash")),
                number(cafe.get("nextOrderCash")), number(cafe.get("storeCount")),
                list(cafe.get("ownedUpgrades")).size(), list(cafe.get("ownedAutomation")).size(),
                list(cafe.get("ownedItems")).size());
    }

    private static void verifyFinalBalance(
            Curriculum curriculum,
            ProgressStore progress,
            Map<String, Object> cafe,
            double spendPercent) {
        require(progress.clearedIds().size() == curriculum.totalTaskCount(),
                "全問題を完走できていません");
        require(number(cafe.get("level")) == 12, "最終カフェレベルへ到達できません");
        require(number(cafe.get("storeCount")) == number(cafe.get("maxStores")),
                "最大店舗数へ到達できません");
        require(list(cafe.get("ownedUpgrades")).size() == 60,
                "通常設備60個を全購入できません");
        require(list(cafe.get("ownedAutomation")).size() == 12,
                "自動営業設備12個を全購入できません");
        require(list(cafe.get("ownedItems")).size() == 12,
                "スペシャルアイテム12個を全購入できません");
        require(spendPercent >= 25.0 && spendPercent <= 45.0,
                "全購入時の投資率が目標25〜45%を外れています: " + spendPercent + "%");

        long taskCash = number(cafe.get("nextOrderCash"));
        long passivePerMinute = number(cafe.get("passiveCashPerMinute"));
        long passiveCap = number(cafe.get("passiveCashCap"));
        require(passivePerMinute <= taskCash / 20L,
                "自動売上が学習1回分の5%/分を超えています");
        require(passiveCap <= taskCash / 2L,
                "次の★までの自動売上が学習1回分の50%を超えています");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("BALANCE FAILED: " + message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cafe(
            ProgressStore progress, ProgressStore.CafeLearningProgress learning) {
        Map<String, Object> root = (Map<String, Object>) progress.toClientJson(learning);
        return (Map<String, Object>) root.get("cafe");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }
}
