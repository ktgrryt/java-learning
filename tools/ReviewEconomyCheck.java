import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * 「今日の1杯目」と、復習がカフェへ渡すもの（ブランド倍率・自動売上の枠・設備費割引）が
 * 意図どおりに効くか確かめる。
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

    /** 販売戦略・常連サービスの両系統を、指定Rankまで順に買う。 */
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
            dailyFirstBonus(dir.resolve("daily.json"));
            reviewBrand(dir.resolve("brand.json"));
            reviewPassiveWindow(dir.resolve("passive.json"));
            reviewEquipmentDiscount(dir.resolve("discount.json"));
            System.out.println("\nREVIEW ECONOMY OK: 今日の1杯目と復習の3経路を確認しました");
        } finally {
            for (String name : new String[] {
                    "gate.json", "daily.json", "brand.json", "passive.json", "discount.json" }) {
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

    // ─── 1. 今日の1杯目は、その日の最初の1問にだけ乗る ─────────────────────
    private static void dailyFirstBonus(Path file) throws Exception {
        System.out.println("\n[今日の1杯目]");
        ProgressStore p = new ProgressStore(file);
        // 設備に★の解放条件は無いので、★1でも常連サービスRank1（連続1日ごと+3%）を買える。
        // 足りるコインを作るため、報酬だけ数問ぶん受け取っておく
        for (int i = 1; i <= 3; i++) {
            p.markCleared("a-1#" + i);
            p.rewardTask(ZERO, "a-1#" + i);
        }
        long cash = value(p, "cash");
        check("Rank1を買う前は今日の1杯目が0%", value(p, "dailyFirstBonusPercent") == 0);

        buyTrack(p, "morning_playlist");
        checkEquals("連続1日 × +3% = 今日の1杯目", value(p, "dailyFirstBonusPercent"), 3);
        check("まだ本日分は未受取", Boolean.TRUE.equals(
                cafe(p, ZERO).get("dailyFirstBonusReady")));

        // 全報酬に掛かる倍率へ混ざっていないこと（ここが崩れると販売戦略と同じ変数になる）
        checkEquals("全報酬の倍率には入らない",
                value(p, "bonusPercent"), value(p, "salesBonusPercent"));

        p.markCleared("a-1#10");
        ProgressStore.CafeAward first = p.rewardTask(ZERO, "a-1#10");
        long plain = value(p, "nextOrderCash");
        check("その日の1問目に+3%が乗る（" + first.cash() + " > " + plain + "）",
                first.cash() > plain);
        check("受取済みになる", Boolean.FALSE.equals(
                cafe(p, ZERO).get("dailyFirstBonusReady")));

        p.markCleared("a-1#11");
        ProgressStore.CafeAward second = p.rewardTask(ZERO, "a-1#11");
        checkEquals("同じ日の2問目には乗らない", second.cash(), plain);

        // 日をまたげばまた受け取れる。進捗ファイルの日付を1日前へ書き換えて確かめる
        p.flushNow();
        rewriteDailyDay(file, LocalDate.now().minusDays(1).toString());
        ProgressStore reloaded = new ProgressStore(file);
        check("日をまたぐと未受取へ戻る", Boolean.TRUE.equals(
                cafe(reloaded, ZERO).get("dailyFirstBonusReady")));
        check("残高は引き継がれる", value(reloaded, "cash") >= cash);
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

    /** 進捗ファイルの「今日の1杯目を払った日」だけを書き換える。 */
    private static void rewriteDailyDay(Path file, String day) throws Exception {
        String json = Files.readString(file);
        String replaced = json.replaceFirst(
                "\"dailyFirstRewardDay\":\"[^\"]*\"",
                "\"dailyFirstRewardDay\":\"" + day + "\"");
        if (replaced.equals(json)) {
            throw new IllegalStateException("dailyFirstRewardDay が保存されていません");
        }
        Files.writeString(file, replaced);
    }
}
