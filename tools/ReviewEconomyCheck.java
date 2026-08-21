import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * 「期限が来た問題の復習報酬」と、復習がカフェへ渡すもの
 * （ブランド倍率・自動売上の枠・設備費割引）、それに確認クイズのチップ（1度目の回答だけ）と、
 * 復習として出し直したクイズ（何も払わず、連続正解だけを進める）が意図どおりに効くか確かめる。
 *
 * <p>どれも「1日1回」「1問1回」「枠は広がらない」という上限が要で、そこが崩れると
 * 無限に稼げてしまう。上限そのものを試すテストなので、増えることより
 * <b>増えないこと</b>を多く確かめている。</p>
 */
public final class ReviewEconomyCheck {

    /** 復習を1問も数えていない状態の学習進捗。 */
    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    private ReviewEconomyCheck() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cafe(
            ProgressStore p, ProgressStore.CafeLearningProgress learning) {
        Map<String, Object> root = (Map<String, Object>) p.toClientJson(learning);
        return (Map<String, Object>) root.get("cafe");
    }

    private static long value(ProgressStore p, String key) {
        return value(p, ZERO, key);
    }

    private static long value(
            ProgressStore p, ProgressStore.CafeLearningProgress learning, String key) {
        Object v = cafe(p, learning).get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static void check(String label, boolean ok) {
        if (!ok) {
            throw new IllegalStateException("NG " + label);
        }
        System.out.println("OK  " + label);
    }

    private static void checkEquals(String label, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "NG " + label + ": expected=" + expected + " actual=" + actual);
        }
        System.out.println("OK  " + label + " (" + actual + ")");
    }

    /** その設備の現在価格。 */
    @SuppressWarnings("unchecked")
    private static long upgradeCost(ProgressStore p, String id) {
        for (Object entry : (Iterable<Object>) cafe(p, ZERO).get("upgrades")) {
            Map<String, Object> upgrade = (Map<String, Object>) entry;
            if (id.equals(upgrade.get("id"))) {
                return upgrade.get("cost") instanceof Number n ? n.longValue() : 0L;
            }
        }
        throw new IllegalStateException("設備が見つかりません: " + id);
    }

    /**
     * その設備が買えるまで、初クリア報酬だけを受け取る。
     *
     * <p>必要な問題数を数え打ちにすると、価格を調整するたびにこの検査が落ちる。
     * ここで試したいのは価格ではなく報酬の上限なので、
     * 「何問か解けば買える」という前提だけを表す。</p>
     */
    private static void earnUntilAffordable(ProgressStore p, String keyPrefix, String upgradeId) {
        long cost = upgradeCost(p, upgradeId);
        for (int i = 1; i <= 200; i++) {
            if (value(p, "cash") >= cost) {
                return;
            }
            p.markCleared(keyPrefix + i);
            p.rewardTask(ZERO, keyPrefix + i);
        }
        throw new IllegalStateException("200問ぶんの報酬でも買えません: " + upgradeId);
    }

    /** 指定した設備を、並べた順（下のRankから）に買う。 */
    private static void buyTrack(ProgressStore p, String... ids) {
        for (String id : ids) {
            ProgressStore.PurchaseResult r = p.purchaseCafeUpgrade(id);
            if (!r.purchased()) {
                throw new IllegalStateException("設備が買えません: " + id + " / " + r.error());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jq-review-economy-");
        try {
            noStarGate(dir.resolve("gate.json"));
            reviewReward(dir.resolve("review-reward.json"));
            reviewBrand(dir.resolve("brand.json"));
            reviewPassiveWindow(dir.resolve("passive.json"));
            reviewEquipmentDiscount(dir.resolve("discount.json"));
            quizTipFirstAnswerOnly(dir.resolve("quiz.json"));
            quizReviewPaysNothing(dir.resolve("quiz-review.json"));
            System.out.println(
                    "\nREVIEW ECONOMY OK: 期限ぶんの報酬・復習の3経路・クイズのチップ"
                            + "・復習のクイズを確認しました");
        } finally {
            for (String name : new String[] { "gate.json", "review-reward.json", "brand.json",
                    "passive.json", "discount.json", "quiz.json", "quiz-review.json" }) {
                Files.deleteIfExists(dir.resolve(name));
            }
            Files.deleteIfExists(dir);
        }
    }

    // ─── 0. 設備に★の解放条件が無いこと ────────────────────────────────────
    /**
     * ★1でも、5系統すべてのRank1と自動営業Rank1が買えること。
     *
     * <p>歯止めはコインだけにする設計なので、足りない理由が「★」になっていたら失敗させる。
     * 上のRankが買えないのは、下のRankを持っていないから（順番の縛り）だけであるべき。</p>
     */
    private static void noStarGate(Path file) {
        System.out.println("\n[設備の★解放条件]");
        ProgressStore p = new ProgressStore(file);
        p.markCleared("g-1#1");

        // ★1のまま、全系統のRank1を買えるだけのコインを用意する
        long need = 100_000L;
        while (value(p, "cash") < need) {
            p.rewardTask(ZERO, "g-1#1");
        }
        checkEquals("★は1のまま", p.clearedIds().size(), 1);

        for (String id : new String[] {
                "welcome_mat", "extra_mugs", "stamp_card", "tip_jar", "morning_playlist" }) {
            ProgressStore.PurchaseResult r = p.purchaseCafeUpgrade(id);
            check("★1で買える: " + id, r.purchased());
        }
        ProgressStore.AutomationPurchaseResult a = p.purchaseCafeAutomation("warming_pot");
        check("★1で買える: warming_pot（自動営業Rank1）", a.purchased());

        // 上のRankが買えないのは順番の縛りだけ。コインが足りていれば買える
        ProgressStore.PurchaseResult rank2 = p.purchaseCafeUpgrade("signboard");
        check("★1でもRank2が買える（下のRankを持っているので）", rank2.purchased());

        // Rank3を飛ばして4を買おうとすると、★ではなく順番の理由で断られる
        ProgressStore.PurchaseResult skip = p.purchaseCafeUpgrade("espresso");
        check("Rankの飛ばし買いは断られる", !skip.purchased());
        check("断る理由が★ではない（" + skip.error() + "）", !skip.error().contains("★"));
    }

    // ─── 1. コインを払うのは「期限が来た問題を復習で通したとき」だけ ─────────
    /**
     * 期限が来た問題を復習で通したときだけ払い、それ以外では1コインも動かないこと。
     *
     * <p>上限は「期限」が作っている ― 通すと次の期限が翌日以降へ動くので、同じ問題を
     * 続けて通しても2回目からは0コインになる。ここが崩れるとクリア済みの問題を
     * 連打して無限に稼げてしまうので、<b>増えないことを多く確かめている</b>。</p>
     */
    private static void reviewReward(Path file) throws Exception {
        System.out.println("\n[期限が来た問題の復習報酬]");
        ProgressStore p = new ProgressStore(file);
        p.markCleared("a-1#1");
        p.markCleared("a-1#2");
        p.markCleared("a-1#3");

        // 今日クリアした問題の期限は明日以降なので、その日のうちに復習しても払わない
        long before = value(p, "cash");
        ProgressStore.ReviewOutcome sameDay = p.recordMasterySubmission("a-1#1", true, true);
        check("今日クリアした問題は期限前（払わない）", !sameDay.duePassed());
        checkEquals("残高は動かない", value(p, "cash"), before);

        // 期限が来た状態は、クリア日と復習予定を過去へ書き換えて作る（実時間を待たない）
        p.flushNow();
        rewriteDates(file, LocalDate.now().minusDays(200).toString());
        p = new ProgressStore(file);

        long oneTask = value(p, "nextOrderCash");
        ProgressStore.ReviewOutcome due = p.recordMasterySubmission("a-1#1", true, true);
        check("期限が来ていた", due.duePassed());
        check("その日に失敗していない", due.cleanRecall());
        ProgressStore.CafeAward paid = p.rewardReview(ZERO, "a-1#1", due.cleanRecall());
        checkEquals("1問クリアの30%を払う", paid.cash(), oneTask * 30 / 100);
        checkEquals("杯は増やさない", paid.cups(), 0);
        check("初回クリアより少ない（" + paid.cash() + " < " + oneTask + "）",
                paid.cash() < oneTask);

        // 通した時点で期限が動くので、同じ日にもう一度通しても払わない
        ProgressStore.ReviewOutcome again = p.recordMasterySubmission("a-1#1", true, true);
        check("同じ日の2回目は期限前（払わない）", !again.duePassed());
        for (int i = 0; i < 10; i++) {
            check("連打しても払わない",
                    !p.recordMasterySubmission("a-1#1", true, true).duePassed());
        }

        // 通常のレッスン画面からの再提出は復習ではない（解答が最初から入っている）。
        // 期限そのものは動く（正解しているので）ため、以降 a-1#2 は期限前になる
        check("通常画面からの再提出では払わない",
                !p.recordMasterySubmission("a-1#2", true).duePassed());
        check("通常画面で通したあとに復習しても払わない（期限が動いている）",
                !p.recordMasterySubmission("a-1#2", true, true).duePassed());
        // 未クリアの問題は、1ケースだけ通った提出などが混ざるので払わない
        check("未クリアの問題では払わない",
                !p.recordMasterySubmission("a-9#9", true, true).duePassed());
        check("失敗した提出では払わない",
                !p.recordMasterySubmission("a-1#3", false, true).duePassed());

        // 同じ日に失敗してから通した回は払うが、「一発で思い出せた」には数えない
        // （失敗では期限が動かないので、a-1#3 は期限が来たまま残っている）
        ProgressStore.ReviewOutcome stumbled = p.recordMasterySubmission("a-1#3", true, true);
        check("失敗してから通した回も期限ぶんは払う", stumbled.duePassed());
        check("その回は一発ではない（マドレーヌは乗らない）", !stumbled.cleanRecall());

        // 上限は素の金額どうしで比べる。設備を最上位まで買っても初回クリアを超えないこと
        p.flushNow();
        rewriteDates(file, LocalDate.now().minusDays(200).toString());
        p = new ProgressStore(file);
        for (String id : new String[] { "morning_playlist", "window_seat", "study_table",
                "loyalty_board" }) {
            earnUntilAffordable(p, "z-1#", id);
            buyTrack(p, id);
        }
        checkEquals("復習手当Rank4で+22%", value(p, "reviewBonusPercent"), 22);
        long taskCash = value(p, "nextOrderCash");
        ProgressStore.ReviewOutcome outcome = p.recordMasterySubmission("a-1#1", true, true);
        check("期限が来ている（200日前へ戻した）", outcome.duePassed());
        long reviewCash = p.rewardReview(ZERO, "a-1#1", false).cash();
        checkEquals("30%を+22%で伸ばす", reviewCash, taskCash * 30 / 100 * 122 / 100);
        check("設備を積んでも初回クリアの80%以下（" + reviewCash + " <= "
                + (taskCash * 80 / 100) + "）", reviewCash <= taskCash * 80 / 100);

        // 最上位まで買った場合は、価格が高すぎてこの検査では作れない。効果値そのものから
        // 「30% × (100+最上位)% が 80% を超えないか」を数で確かめる（上限そのものの検査）
        long topPercent = maxEffectValue(p, "review");
        checkEquals("復習手当の最上位は+100%", topPercent, 100);
        check("30% × (100+" + topPercent + ")% ≦ 80%",
                30 * (100 + topPercent) / 100 <= 80);
    }

    /** その系統の設備が持つ効果値のうち、いちばん大きいもの（＝最上位Rankの効果）。 */
    @SuppressWarnings("unchecked")
    private static long maxEffectValue(ProgressStore p, String effectType) {
        long max = 0;
        for (Object entry : (Iterable<Object>) cafe(p, ZERO).get("upgrades")) {
            Map<String, Object> upgrade = (Map<String, Object>) entry;
            if (effectType.equals(upgrade.get("effectType"))
                    && upgrade.get("effectValue") instanceof Number n) {
                max = Math.max(max, n.longValue());
            }
        }
        if (max == 0) {
            throw new IllegalStateException("系統が見つかりません: " + effectType);
        }
        return max;
    }

    // ─── 2. 復習はブランド倍率を1問1回だけ育てる ────────────────────────────
    private static void reviewBrand(Path file) {
        System.out.println("\n[復習とブランド倍率]");
        ProgressStore p = new ProgressStore(file);
        for (int i = 1; i <= 5; i++) {
            p.markCleared("b-1#" + i);
        }
        checkEquals("復習前の復習ぶんは0", value(p, "reviewBrandBasisPoints"), 0);

        p.recordMasterySubmission("b-1#1", true);
        long one = value(p, "reviewBrandBasisPoints");
        checkEquals("1問復習で40bp", one, 40);
        checkEquals("数えた問題数", value(p, "reviewedTasks"), 1);

        for (int i = 0; i < 10; i++) {
            p.recordMasterySubmission("b-1#1", true);
        }
        checkEquals("同じ問題を連打しても増えない",
                value(p, "reviewBrandBasisPoints"), one);

        p.recordMasterySubmission("b-1#2", true);
        checkEquals("別の問題なら増える", value(p, "reviewBrandBasisPoints"), 80);

        // 未クリアの問題に正解しても復習として数えない
        p.recordMasterySubmission("b-9#9", true);
        checkEquals("未クリア問題は数えない", value(p, "reviewedTasks"), 2);

        // 初回クリアの170bpを超えないこと（超えると復習の方が儲かる）。
        // 復習ノートを持つと4倍の160bpになるので、そこが上限
        long perTask = value(p, "reviewBrandBasisPoints") / value(p, "reviewedTasks");
        check("復習1問(" + perTask + "bp) < 初回クリア(170bp)", perTask < 170);
        check("復習ノートで4倍にしても超えない（" + (perTask * 4) + "bp）", perTask * 4 < 170);
    }

    // ─── 3. 復習は自動売上の枠を戻すが、広げはしない ────────────────────────
    private static void reviewPassiveWindow(Path file) throws Exception {
        System.out.println("\n[復習と自動売上の枠]");
        ProgressStore p = new ProgressStore(file);
        for (int i = 1; i <= 6; i++) {
            p.markCleared("c-1#" + i);
        }
        p.rewardTask(ZERO, "c-1#1");
        long cap = value(p, "passiveCashCap");
        long oneTask = value(p, "nextOrderCash");
        checkEquals("枠は1問売上の5倍", cap, oneTask * 5);
        checkEquals("★直後の残枠は満タン", value(p, "passiveCashRemaining"), cap);

        // 使い切った状態を実時間で作ると1000分かかる（最下位設備で2.5コイン/分）。
        // 受け取り済み額だけを進捗ファイルへ書いて読み直し、同じ状態から始める。
        p.flushNow();
        long spent = cap;
        rewriteNumber(file, "passiveCashSinceTask", spent);
        p = new ProgressStore(file);
        checkEquals("使い切ると残枠0", value(p, "passiveCashRemaining"), 0);

        // 同じ復習でブランド倍率も少し育つので、枠と1問分の額は再計算される。
        // 戻ったのが「ちょうど1問分」かは、受け取り済み額から引いて確かめる。
        p.recordMasterySubmission("c-1#2", true);
        long capNow = value(p, "passiveCashCap");
        long oneNow = value(p, "nextOrderCash");
        check("ブランド倍率も育っている", oneNow > oneTask);
        checkEquals("復習1問で1問分だけ戻る",
                value(p, "passiveCashRemaining"), capNow - (spent - oneNow));

        // 実際に差し引くのは、1問分の売上額が分かる自動売上の集計時
        String session = "test-session";
        p.startCafePassiveSales(session, ZERO);
        p.collectCafePassiveSales(session, ZERO);
        p.flushNow();
        checkEquals("戻したぶんは集計時に使い切る", readNumber(file, "reviewPassiveCredits"), 0);
        check("戻った枠は残っている", value(p, "passiveCashRemaining") >= oneNow);

        // 何問復習しても、枠そのもの（5問分）より広くはならない
        for (int i = 1; i <= 6; i++) {
            p.recordMasterySubmission("c-1#" + i, true);
        }
        p.collectCafePassiveSales(session, ZERO);
        checkEquals("上限まで戻ったら満タン止まり",
                value(p, "passiveCashRemaining"), value(p, "passiveCashCap"));
        checkEquals("枠そのものは1問売上の5倍のまま",
                value(p, "passiveCashCap"), value(p, "nextOrderCash") * 5);
    }

    // ─── 4. 設備費の割引はアイテム1枚ぶんだけで、他の要素で動かない ──────────
    /**
     * アイテムは1枚1効果なので、設備費の割引もマイスター工具箱の20%だけであること。
     *
     * 以前は復習率に比例する割引をもう1つ持っていて、2枚が同じ数値を押し合っていた。
     * 効果を1枚1つへ整理した結果、ここは常に20%で動かないのが正しい。
     */
    private static void reviewEquipmentDiscount(Path file) {
        System.out.println("\n[設備費の割引]");
        ProgressStore p = new ProgressStore(file);
        int tasks = 300;
        for (int i = 1; i <= tasks; i++) {
            p.markCleared("d-1#" + i);
        }
        checkEquals("アイテムを持たない間は0%", value(p, "equipmentDiscountPercent"), 0);

        // マイスター工具箱（★240）は完走救済で受け取れる
        p.ensureCafeCompletionCatchUp(tasks, tasks);
        checkEquals("工具箱で20%", value(p, "equipmentDiscountPercent"), 20);

        for (int i = 1; i <= tasks; i++) {
            p.recordMasterySubmission("d-1#" + i, true);
        }
        checkEquals("全問復習しても20%のまま（割引は1枚ぶんだけ）",
                value(p, "equipmentDiscountPercent"), 20);
        checkEquals("復習率は画面表示用に数えている",
                value(p, "reviewedTaskPercent"), 100);
    }

    // ─── 5. 確認クイズのチップは1度目の回答だけ ────────────────────────────
    /**
     * 誤答のあとに表示された正解を押しても、チップが出ないこと。
     *
     * <p>不正解のフィードバックは正解の記号を出すので、答え直しにも払うとクイズが
     * 「読んで押すだけの入金口」になる。復習の提出でコインを払わないのと同じ上限。</p>
     */
    private static void quizTipFirstAnswerOnly(Path file) {
        System.out.println("\n[確認クイズのチップ]");
        ProgressStore p = new ProgressStore(file);

        ProgressStore.CafeAward first = p.recordQuiz("e-1", 0, 0, true, ZERO);
        check("1度目の回答で正解 → チップが出る（" + first.cash() + "）", first.cash() > 0);
        checkEquals("同じクイズを押し直しても増えない",
                p.recordQuiz("e-1", 0, 0, true, ZERO).cash(), 0);

        checkEquals("不正解ではチップが出ない",
                p.recordQuiz("e-1", 1, 1, false, ZERO).cash(), 0);
        checkEquals("答え直して正解してもチップは出ない",
                p.recordQuiz("e-1", 1, 0, true, ZERO).cash(), 0);

        // 残高でも確かめる（払わないのに他の経路で増えていたら、それも取り逃したい）
        long before = value(p, "cash");
        for (int i = 0; i < 10; i++) {
            p.recordQuiz("e-1", 1, 0, true, ZERO);
        }
        checkEquals("何度押しても残高は動かない", value(p, "cash"), before);
    }

    // ─── 6. 復習として出し直したクイズ ─────────────────────────────────────
    /**
     * 復習のクイズは、チップも★も動かさず、連続正解だけを進めること。
     *
     * <p>📣ひらめきメガホンの取り逃しを無くすために足した経路（{@code /api/quiz} の
     * {@code review}）。ここで払ってしまうと、クリア済みの問題を解き直しても
     * コインが出ないという原則の例外になる。</p>
     */
    private static void quizReviewPaysNothing(Path file) {
        System.out.println("\n[復習として出し直したクイズ]");
        ProgressStore p = new ProgressStore(file);

        // 1度目の回答でチップを出し、残高を作ってから復習の回答を試す
        p.recordQuiz("r-1", 0, 0, true, ZERO);
        long before = value(p, "cash");
        long streakBefore = value(p, "quizFirstStreak");

        p.recordQuizReview("r-1", 0, true);
        checkEquals("復習で正解しても残高は動かない", value(p, "cash"), before);
        checkEquals("復習で正解しても連続（初回答ぶん）は動かない",
                value(p, "quizFirstStreak"), streakBefore);
        checkEquals("復習の連続正解が1つ進む", value(p, "quizReviewRun"), 1);

        p.recordQuizReview("r-1", 0, true);
        checkEquals("同じクイズを解き直しても増えない（覚えた1問の繰り返しを数えない）",
                value(p, "quizReviewRun"), 1);

        p.recordQuizReview("r-1", 1, true);
        checkEquals("別のクイズなら進む", value(p, "quizReviewRun"), 2);

        p.recordQuizReview("r-1", 2, false);
        checkEquals("復習で間違えると0に戻る", value(p, "quizReviewRun"), 0);
        checkEquals("戻ってもチップは出ない", value(p, "cash"), before);
    }

    /** 進捗ファイルの数値フィールドを書き換える（実時間の経過を待たずに状態を作る）。 */
    private static void rewriteNumber(Path file, String key, long value) throws Exception {
        String json = Files.readString(file);
        String replaced = json.replaceFirst(
                "\"" + key + "\":-?[0-9]+", "\"" + key + "\":" + value);
        if (replaced.equals(json)) {
            throw new IllegalStateException(key + " が保存されていません");
        }
        Files.writeString(file, replaced);
    }

    private static long readNumber(Path file, String key) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?[0-9]+)")
                .matcher(Files.readString(file));
        if (!m.find()) {
            throw new IllegalStateException(key + " が保存されていません");
        }
        return Long.parseLong(m.group(1));
    }

    /**
     * 進捗ファイルのクリア日と復習予定の日付を、まとめて過去へ書き換える。
     *
     * <p>期限は「クリア日（または最後に復習した日）＋間隔」で決まり、保存しているのは
     * 日付だけなので、日付を戻せば実時間を待たずに「期限が来た」状態を作れる。</p>
     */
    private static void rewriteDates(Path file, String day) throws Exception {
        String json = Files.readString(file);
        String replaced = json
                .replaceAll("\"clearedAt\":\"[^\"]*\"", "\"clearedAt\":\"" + day + "\"")
                .replaceAll("\"at\":\"[^\"]*\"", "\"at\":\"" + day + "\"");
        if (replaced.equals(json)) {
            throw new IllegalStateException("clearedAt が保存されていません");
        }
        Files.writeString(file, replaced);
    }
}
