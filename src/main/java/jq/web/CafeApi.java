package jq.web;

import jq.content.Chapter;
import jq.content.Curriculum;
import jq.progress.ProgressStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * カフェ画面のAPI（{@code /api/cafe/*}）。
 *
 * <p>設備・アイテム・自動営業・出店・終盤投資の購入と、画面表示中の自動売上を受ける。
 * 学習の採点（{@code /api/submit} や {@code /api/quiz}）とは目的が別で、
 * 混ぜておくと {@link ApiHandler} が「採点の入口」と「経営の入口」の両方になってしまうため
 * ここへ分けている。振り分けは {@code ApiHandler} の1箇所に残してある。</p>
 *
 * <p>どの口も、応答に {@code delta.progress} を載せて返す。買った直後の残高や解放状況が
 * 画面へ即座に反映されるようにするためで、問題やレッスンの進捗は動かないので送らない。</p>
 *
 * <p>経済の規則そのものは持たない（{@code jq.progress.CafeEconomy} にある）。
 * ここが行うのは入力の確認と、断られた理由をHTTPの400へ変えることだけである。</p>
 */
final class CafeApi {

    private final ProgressStore progress;
    /** いま読み込まれている教材。{@code ApiHandler} が読み直すので、毎回引き直す。 */
    private final Supplier<Curriculum> curriculum;

    CafeApi(ProgressStore progress, Supplier<Curriculum> curriculum) {
        this.progress = progress;
        this.curriculum = curriculum;
    }

    /**
     * 制覇した章数と、その章に含まれる問題数。
     *
     * 短い章だけの先取りを有利にしないため、章数と問題数の両方をカフェへ渡す。
     * 章にどの問題が属するかは教材側しか知らないので、ここで数える。
     */
    static ProgressStore.CafeLearningProgress learningProgress(
            Curriculum c, Set<String> cleared) {
        int chapterCount = 0;
        int masteredChapterTasks = 0;
        for (Chapter chapter : c.chapters()) {
            if (c.isChapterCleared(chapter, cleared)) {
                chapterCount++;
                masteredChapterTasks += c.taskCount(chapter);
            }
        }
        return new ProgressStore.CafeLearningProgress(chapterCount, masteredChapterTasks);
    }

    /** 報酬（コインと杯数、アイテムの発動）を画面へ渡す形にする。 */
    static Object awardJson(ProgressStore.CafeAward award) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cash", award.cash());
        m.put("cups", award.cups());
        m.put("itemEvents", award.itemEvents());
        return m;
    }

    Object purchaseUpgrade(Map<String, Object> body) {
        String id = ApiHandler.requireString(body, "id");
        Curriculum c = curriculum.get();
        Set<String> cleared = progress.clearedIds();
        ProgressStore.CafeLearningProgress cafeLearning = learningProgress(c, cleared);
        ProgressStore.PurchaseResult purchase = progress.purchaseCafeUpgrade(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> upgrade = new LinkedHashMap<>();
        upgrade.put("id", purchase.upgrade().id());
        upgrade.put("name", purchase.upgrade().name());
        upgrade.put("emoji", purchase.upgrade().emoji());
        upgrade.put("tier", purchase.upgrade().tier());
        upgrade.put("replacedName", purchase.replacedUpgrade() == null
                ? null : purchase.replacedUpgrade().name());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upgrade", upgrade);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object purchaseAutomation(Map<String, Object> body) {
        String id = ApiHandler.requireString(body, "id");
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        ProgressStore.AutomationPurchaseResult purchase = progress.purchaseCafeAutomation(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> automation = new LinkedHashMap<>();
        automation.put("id", purchase.automation().id());
        automation.put("name", purchase.automation().name());
        automation.put("emoji", purchase.automation().emoji());
        automation.put("tier", purchase.automation().tier());
        automation.put("replacedName", purchase.replacedAutomation() == null
                ? null : purchase.replacedAutomation().name());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("automation", automation);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object passiveSales(Map<String, Object> body, String action) {
        String sessionId = ApiHandler.requireString(body, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > 100) {
            throw new BadRequest("自動売上のセッションIDが不正です");
        }
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        ProgressStore.PassiveSalesResult passive = switch (action) {
            case "start" -> progress.startCafePassiveSales(sessionId, cafeLearning);
            case "collect" -> progress.collectCafePassiveSales(sessionId, cafeLearning);
            case "stop" -> progress.stopCafePassiveSales(sessionId, cafeLearning);
            default -> throw new IllegalArgumentException("unknown passive action: " + action);
        };

        Map<String, Object> passiveJson = new LinkedHashMap<>();
        passiveJson.put("cash", passive.cash());
        passiveJson.put("cashPerMinute", passive.cashPerMinute());
        passiveJson.put("active", passive.active());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passive", passiveJson);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object expand() {
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        ProgressStore.ExpansionResult expansion = progress.expandCafeNetwork();
        if (!expansion.expanded()) {
            throw new BadRequest(expansion.error());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previousStores", expansion.previousStores());
        result.put("addedStores", expansion.addedStores());
        result.put("storeCount", expansion.storeCount());
        result.put("cost", expansion.cost());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expansion", result);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object purchaseInvestment() {
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        ProgressStore.InvestmentPurchaseResult purchase = progress.purchaseCafeInvestment();
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        ProgressStore.CafeInvestment investment = purchase.investment();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", investment.level());
        result.put("name", investment.name());
        result.put("emoji", investment.emoji());
        result.put("cost", investment.cost());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("investment", result);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object purchaseItem(Map<String, Object> body) {
        String id = ApiHandler.requireString(body, "id");
        Curriculum c = curriculum.get();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        ProgressStore.ItemPurchaseResult purchase = progress.purchaseCafeItem(id);
        if (!purchase.purchased()) {
            throw new BadRequest(purchase.error());
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", purchase.item().id());
        item.put("name", purchase.item().name());
        item.put("emoji", purchase.item().emoji());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
        return m;
    }

    Object markItemsSeen() {
        Curriculum c = curriculum.get();
        progress.markCafeItemsSeen();
        ProgressStore.CafeLearningProgress cafeLearning =
                learningProgress(c, progress.clearedIds());
        return Map.of("delta", Map.of("progress", progress.toClientJson(cafeLearning)));
    }}
