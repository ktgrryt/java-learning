import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * スペシャルアイテム12種が、条件を満たしたときだけ現れるか確かめる。
 *
 * <p>9種は達成条件（学ぶ過程）で、3種は★数と累計コイン（学習の節目）で解放する。
 * 達成条件のうち {@code review_200} と {@code flawless_25} は重い2つで、
 * ここでは「1問足りないと出ない」ところまで見る。</p>
 */
public final class AchievementCheck {

    /** 復習を1問も数えていない状態の学習進捗。 */
    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    /** 達成条件で解放される9種。 */
    private static final List<String> ACHIEVEMENT_ITEMS = List.of(
            "golden_bean", "first_try_tamper", "persistence_dripper", "attendance_calendar",
            "food_truck", "quiz_crown", "fortune_cat",
            "quiz_festival_pass", "lifelong_trophy");

    /** ★数と累計コインで解放される3種。完走時の救済で贈るのもこの3つ。 */
    private static final List<String> MILESTONE_ITEMS =
            List.of("lucky_coin", "fever_bell", "java_relic");

    private AchievementCheck() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cafeOf(ProgressStore p) {
        Map<String, Object> root = (Map<String, Object>) p.toClientJson(ZERO);
        return (Map<String, Object>) root.get("cafe");
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ProgressStore p) {
        Map<String, Object> root = (Map<String, Object>) p.toClientJson(ZERO);
        Map<String, Object> cafe = (Map<String, Object>) root.get("cafe");
        return (List<Map<String, Object>>) (List<?>) cafe.get("items");
    }

    private static boolean has(ProgressStore p, String id) {
        return items(p).stream().anyMatch(i -> id.equals(i.get("id")));
    }

    /**
     * 全12種が見えている状態を作る。
     *
     * 未発見のアイテムはクライアントJSONへ載らないので、カード全体を検査するには
     * 達成条件と★条件の両方を満たしておく必要がある。
     */
    private static ProgressStore everything() throws Exception {
        Path dir = Files.createTempDirectory("jq-all-items-");
        Path file = dir.resolve("progress.json");
        ProgressStore p = new ProgressStore(file);
        for (int i = 1; i <= 300; i++) {
            p.markCleared("all#" + i);
        }
        p.ensureCafeCompletionCatchUp(300, 300);          // ★条件の3種
        for (int i = 0; i < 20; i++) {
            p.recordQuiz("allq", i, 0, true);             // quiz_streak_20
        }
        for (int i = 0; i < 10; i++) {
            p.recordAttempt("all#1");                     // persistent_clear
        }
        p.noteChapterAchievements(List.of("all#1", "all#2"));  // 章の2条件
        // store_5: コインを貯めて5店舗まで広げる
        ProgressStore.CafeLearningProgress zero = ZERO;
        for (int guard = 0; guard < 10_000; guard++) {
            Map<String, Object> cafe = cafeOf(p);
            if (number(cafe.get("storeCount")) >= 5) {
                break;
            }
            Object cost = cafe.get("expansionCost");
            if (cost instanceof Number n && number(cafe.get("cash")) >= n.longValue()) {
                p.expandCafeNetwork();
            } else {
                p.rewardTask(zero, "all#1");
            }
        }
        for (int i = 1; i <= 200; i++) {
            p.recordMasterySubmission("all#" + i, true);  // review_200
        }
        p.flushNow();
        String json = Files.readString(file);
        StringBuilder days = new StringBuilder();
        LocalDate day = LocalDate.now().minusDays(6);
        for (int i = 0; i < 7; i++) {
            days.append(i == 0 ? "\"" : ",\"").append(day.plusDays(i)).append("\"");
        }
        Files.writeString(file, json.replaceFirst("\"clearDates\":\\[[^\\]]*\\]",
                "\"clearDates\":[" + days + "]"));       // streak_7
        ProgressStore reloaded = new ProgressStore(file);
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
        return reloaded;
    }

    private static void check(String label, boolean actual, boolean expected) {
        if (actual != expected) {
            throw new IllegalStateException("NG " + label + ": expected=" + expected);
        }
        System.out.println((expected ? "解放された  " : "まだ出ない  ") + label);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jq-achievement-");
        Path file = dir.resolve("progress.json");
        try {
            // ── 1. 何もしていない状態では、達成型アイテムは1つも現れない ──────
            ProgressStore fresh = new ProgressStore(file);
            for (String id : ACHIEVEMENT_ITEMS) {
                check("初期状態: " + id, has(fresh, id), false);
            }

            // ── 2. ヒントなし・一発で25問 → 生涯学習トロフィー（重い条件） ────
            for (int i = 1; i <= 24; i++) {
                fresh.markCleared("x-1#" + i);
            }
            check("24問では出ない: lifelong_trophy", has(fresh, "lifelong_trophy"), false);
            fresh.markCleared("x-1#25");
            check("25問連続で無傷: lifelong_trophy", has(fresh, "lifelong_trophy"), true);

            // ── 3. 同じ日に15問 → コンボスタンプ帳 ───────────────────────────
            check("15問は超えている: golden_bean", has(fresh, "golden_bean"), true);

            // ── 3.5 1問へ累計10回提出 → 粘りのドリッパー ────────────────────
            check("まだ出ない: persistence_dripper", has(fresh, "persistence_dripper"), false);
            for (int i = 0; i < 9; i++) {
                fresh.recordAttempt("y-1#1");
            }
            fresh.markCleared("y-1#1");
            check("9回では出ない: persistence_dripper",
                    has(fresh, "persistence_dripper"), false);
            fresh.recordAttempt("y-1#1");
            check("累計10回提出: persistence_dripper",
                    has(fresh, "persistence_dripper"), true);
            // 粘った問題が混ざると無傷の連続は途切れる。解放済みなら消えない
            check("解放は取り消されない: lifelong_trophy",
                    has(fresh, "lifelong_trophy"), true);

            // ── 4. クイズ20問連続の初回正解 → ひらめきメガホン ───────────────
            for (int i = 0; i < 19; i++) {
                fresh.recordQuiz("q-1", i, 0, true);
            }
            check("19問では出ない: quiz_crown", has(fresh, "quiz_crown"), false);
            fresh.recordQuiz("q-1", 19, 0, true);
            check("20問連続で初回正解: quiz_crown", has(fresh, "quiz_crown"), true);
            fresh.recordQuiz("q-2", 0, 1, false);
            check("間違えても残る: quiz_crown", has(fresh, "quiz_crown"), true);

            // ── 5. 章をヒントなし・同じ日に全問クリア → ケーキとしおり ────────
            check("まだ出ない: first_try_tamper", has(fresh, "first_try_tamper"), false);
            fresh.noteChapterAchievements(List.of("x-1#1", "x-1#2", "x-1#3"));
            check("ヒントなしで章制覇: first_try_tamper",
                    has(fresh, "first_try_tamper"), true);
            check("同じ日に章制覇: fortune_cat", has(fresh, "fortune_cat"), true);

            // ── 6. ヒントを使った章では、ケーキの条件を満たさない ────────────
            Path other = dir.resolve("other.json");
            ProgressStore hinted = new ProgressStore(other);
            hinted.revealHint("z-1#1", 0);
            hinted.markCleared("z-1#1");
            hinted.markCleared("z-1#2");
            hinted.noteChapterAchievements(List.of("z-1#1", "z-1#2"));
            check("ヒントを使った章: first_try_tamper",
                    has(hinted, "first_try_tamper"), false);
            check("同じ日ではある: fortune_cat", has(hinted, "fortune_cat"), true);
            // 全問へ再正解すれば、ヒントを使った章でも回収できる
            hinted.recordMasterySubmission("z-1#1", true);
            hinted.recordMasterySubmission("z-1#2", true);
            hinted.noteChapterAchievements(List.of("z-1#1", "z-1#2"));
            check("ヒント使用後に章を復習: first_try_tamper",
                    has(hinted, "first_try_tamper"), true);

            // ── 7. 7日連続の履歴を持つ進捗ファイルは、起動時に解放される ─────
            fresh.flushNow();
            String json = Files.readString(file);
            StringBuilder days = new StringBuilder();
            LocalDate day = LocalDate.now().minusDays(6);
            for (int i = 0; i < 7; i++) {
                days.append(i == 0 ? "\"" : ",\"").append(day.plusDays(i)).append("\"");
            }
            json = json.replaceFirst("\"clearDates\":\\[[^\\]]*\\]",
                    "\"clearDates\":[" + days + "]");
            Files.writeString(file, json);
            ProgressStore reloaded = new ProgressStore(file);
            check("7日連続の履歴で起動: attendance_calendar",
                    has(reloaded, "attendance_calendar"), true);
            check("再読み込みでも残る: lifelong_trophy",
                    has(reloaded, "lifelong_trophy"), true);

            // ── 8. 初回の無傷記録を逃しても、復習の連続正解で作り直せる ───────
            Path replayFile = dir.resolve("replay.json");
            ProgressStore replay = new ProgressStore(replayFile);
            for (int i = 1; i <= 25; i++) {
                replay.recordAttempt("r-1#" + i);
                replay.recordAttempt("r-1#" + i);
                replay.markCleared("r-1#" + i);
            }
            check("初回を逃した状態: lifelong_trophy",
                    has(replay, "lifelong_trophy"), false);
            for (int i = 0; i < 40; i++) {
                replay.recordMasterySubmission("r-1#1", true);
            }
            check("同じ復習問題の連打では出ない: lifelong_trophy",
                    has(replay, "lifelong_trophy"), false);
            replay.recordMasterySubmission("r-1#1", false);
            for (int i = 1; i <= 24; i++) {
                replay.recordMasterySubmission("r-1#" + i, true);
            }
            check("復習24問では出ない: lifelong_trophy",
                    has(replay, "lifelong_trophy"), false);
            replay.flushNow();
            replay = new ProgressStore(replayFile);
            replay.recordMasterySubmission("r-1#25", true);
            check("再起動をまたぐ復習25問: lifelong_trophy",
                    has(replay, "lifelong_trophy"), true);

            // ── 9. 復習で異なる200問 → 復習ノート（重い条件） ────────────────
            Path reviewFile = dir.resolve("review.json");
            ProgressStore reviewer = new ProgressStore(reviewFile);
            for (int i = 1; i <= 200; i++) {
                reviewer.markCleared("v-1#" + i);
            }
            check("クリアだけでは出ない: quiz_festival_pass",
                    has(reviewer, "quiz_festival_pass"), false);
            for (int i = 1; i <= 199; i++) {
                reviewer.recordMasterySubmission("v-1#" + i, true);
            }
            check("復習199問では出ない: quiz_festival_pass",
                    has(reviewer, "quiz_festival_pass"), false);
            reviewer.recordMasterySubmission("v-1#200", true);
            check("復習200問: quiz_festival_pass",
                    has(reviewer, "quiz_festival_pass"), true);
            // 同じ問題の連打では数が増えないことも確かめる
            Path spamFile = dir.resolve("spam.json");
            ProgressStore spam = new ProgressStore(spamFile);
            spam.markCleared("w-1#1");
            for (int i = 0; i < 300; i++) {
                spam.recordMasterySubmission("w-1#1", true);
            }
            check("同じ1問を300回復習しても出ない: quiz_festival_pass",
                    has(spam, "quiz_festival_pass"), false);

            // ── 10. 初クリア日がばらばらでも、同日復習と章の再制覇で回収できる ─
            Path datedFile = dir.resolve("dated.json");
            ProgressStore dated = new ProgressStore(datedFile);
            for (int i = 1; i <= 14; i++) {
                dated.markCleared("d-1#" + i);
            }
            dated.flushNow();
            String datedJson = Files.readString(datedFile);
            String today = LocalDate.now().toString();
            for (int i = 1; i <= 14; i++) {
                String oldDay = LocalDate.now().minusDays(i).toString();
                datedJson = datedJson.replaceFirst(
                        "\\\"clearedAt\\\":\\\"" + today + "\\\"",
                        "\\\"clearedAt\\\":\\\"" + oldDay + "\\\"");
            }
            Files.writeString(datedFile, datedJson);
            dated = new ProgressStore(datedFile);
            dated.markCleared("d-1#15");
            check("初クリア日が分散: golden_bean", has(dated, "golden_bean"), false);
            dated.noteChapterAchievements(List.of("d-1#1", "d-1#2"));
            check("初クリア日が分散: fortune_cat", has(dated, "fortune_cat"), false);
            for (int i = 1; i <= 15; i++) {
                dated.recordMasterySubmission("d-1#" + i, true);
            }
            check("同じ日に異なる15問を復習: golden_bean", has(dated, "golden_bean"), true);
            dated.noteChapterAchievements(List.of("d-1#1", "d-1#2"));
            check("同じ日に章を復習: fortune_cat", has(dated, "fortune_cat"), true);

            // ── 11. カフェを育てず完走しても、節目型2アイテムを受け取れる ─────
            Path catchUpFile = dir.resolve("catch-up.json");
            ProgressStore catchUp = new ProgressStore(catchUpFile);
            for (int i = 1; i <= 475; i++) {
                catchUp.markCleared("c-1#" + i);
            }
            check("未完走では記念品を贈らない",
                    catchUp.ensureCafeCompletionCatchUp(474, 475) == 0, true);
            int grantedItems = catchUp.ensureCafeCompletionCatchUp(475, 475);
            List<Map<String, Object>> milestoneItems = items(catchUp).stream()
                    .filter(item -> MILESTONE_ITEMS.contains(item.get("id")))
                    .toList();
            check("完走時に節目型アイテムを贈る", grantedItems == MILESTONE_ITEMS.size(), true);
            check("節目型3アイテムを発見", milestoneItems.size() == MILESTONE_ITEMS.size(), true);
            check("節目型3アイテムを所持",
                    milestoneItems.stream().allMatch(item -> Boolean.TRUE.equals(item.get("owned"))),
                    true);
            check("完走記念品は重複しない",
                    catchUp.ensureCafeCompletionCatchUp(475, 475) == 0, true);

            // ── 12. アイテムは1種類ずつしか持てない ─────────────────────────
            ProgressStore.ItemPurchaseResult again = catchUp.purchaseCafeItem("lucky_coin");
            check("所持済みアイテムは買えない", !again.purchased(), true);
            System.out.println("    （理由: " + again.error() + "）");
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (Map<String, Object> item : items(catchUp)) {
                check("IDが重複していない: " + item.get("id"),
                        ids.add(String.valueOf(item.get("id"))), true);
            }

            // ── 13. 1枚のカードが持つ効果は1つだけ ─────────────────────────
            // 効果を束ねると「何のカードか」が言えなくなるので、ここで縛っておく。
            // ラッキーコインだけは確率と倍率の対で1つの効果を表すため2つ持つ。
            for (Map<String, Object> item : items(everything())) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> effects =
                        (List<Map<String, Object>>) (List<?>) item.get("effects");
                int allowed = "lucky_coin".equals(item.get("id")) ? 2 : 1;
                check("効果は" + allowed + "つ: " + item.get("name"),
                        effects.size() == allowed, true);
            }

            System.out.println("\nACHIEVEMENTS OK: 12種の解放条件・重い2種・完走時救済を確認しました");
        } finally {
            for (String name : new String[] {
                    "catch-up.json", "dated.json", "spam.json", "review.json",
                    "replay.json", "other.json", "progress.json" }) {
                Files.deleteIfExists(dir.resolve(name));
            }
            Files.deleteIfExists(dir);
        }
    }
}
