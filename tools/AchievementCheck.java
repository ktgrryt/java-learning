import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 達成条件で解放されるアイテム9種が、条件を満たしたときだけ現れるか確かめる。 */
public final class AchievementCheck {

    private AchievementCheck() {
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ProgressStore p) {
        Map<String, Object> root =
                (Map<String, Object>) p.toClientJson(new ProgressStore.CafeLearningProgress(0, 0));
        Map<String, Object> cafe = (Map<String, Object>) root.get("cafe");
        return (List<Map<String, Object>>) (List<?>) cafe.get("items");
    }

    private static boolean has(ProgressStore p, String id) {
        return items(p).stream().anyMatch(i -> id.equals(i.get("id")));
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
            for (String id : List.of("first_try_tamper", "prep_pot", "attendance_calendar",
                    "quiz_bell", "unscathed_medal", "one_sitting_bookmark",
                    "persistence_dripper", "food_truck", "clover_coaster")) {
                check("初期状態: " + id, has(fresh, id), false);
            }

            // ── 2. ヒントなし・一発で10問 → 一発仕上げのタンパー ─────────────
            for (int i = 1; i <= 9; i++) {
                fresh.markCleared("x-1#" + i);
            }
            check("9問では出ない: first_try_tamper", has(fresh, "first_try_tamper"), false);
            fresh.markCleared("x-1#10");
            check("10問連続: first_try_tamper", has(fresh, "first_try_tamper"), true);

            // ── 3. 同じ日に15問 → 仕込み用の大鍋 ─────────────────────────────
            check("10問では出ない: prep_pot", has(fresh, "prep_pot"), false);
            for (int i = 11; i <= 15; i++) {
                fresh.markCleared("x-1#" + i);
            }
            check("同じ日に15問: prep_pot", has(fresh, "prep_pot"), true);

            // ── 4. 10回以上提出してクリア → 粘りのドリッパー ─────────────────
            check("まだ出ない: persistence_dripper", has(fresh, "persistence_dripper"), false);
            for (int i = 0; i < 10; i++) {
                fresh.recordAttempt("y-1#1");
            }
            fresh.markCleared("y-1#1");
            check("10回提出してクリア: persistence_dripper",
                    has(fresh, "persistence_dripper"), true);
            // 粘った問題が混ざると一発連続は途切れる。すでに解放済みなので消えない
            check("解放は取り消されない: first_try_tamper",
                    has(fresh, "first_try_tamper"), true);

            // ── 5. クイズ20問連続の初回正解 → 早押しベル ─────────────────────
            for (int i = 0; i < 19; i++) {
                fresh.recordQuiz("q-1", i, 0, true);
            }
            check("19問では出ない: quiz_bell", has(fresh, "quiz_bell"), false);
            fresh.recordQuiz("q-1", 19, 0, true);
            check("20問連続で初回正解: quiz_bell", has(fresh, "quiz_bell"), true);
            // 間違えたら連続は0へ戻るが、解放済みのベルは残る
            fresh.recordQuiz("q-2", 0, 1, false);
            check("間違えても残る: quiz_bell", has(fresh, "quiz_bell"), true);

            // ── 6. 章をヒントなし・同じ日に全問クリア → 勲章としおり ─────────
            check("まだ出ない: unscathed_medal", has(fresh, "unscathed_medal"), false);
            fresh.noteChapterAchievements(List.of("x-1#1", "x-1#2", "x-1#3"));
            check("ヒントなしで章制覇: unscathed_medal", has(fresh, "unscathed_medal"), true);
            check("同じ日に章制覇: one_sitting_bookmark",
                    has(fresh, "one_sitting_bookmark"), true);

            // ── 7. ヒントを使った章では勲章の条件を満たさない ────────────────
            Path other = dir.resolve("other.json");
            ProgressStore hinted = new ProgressStore(other);
            hinted.revealHint("z-1#1", 0);
            hinted.markCleared("z-1#1");
            hinted.markCleared("z-1#2");
            hinted.noteChapterAchievements(List.of("z-1#1", "z-1#2"));
            check("ヒントを使った章: unscathed_medal", has(hinted, "unscathed_medal"), false);
            check("同じ日ではある: one_sitting_bookmark",
                    has(hinted, "one_sitting_bookmark"), true);

            // ── 8. クイズに累計50問正解 → 四つ葉のコースター ─────────────────
            ProgressStore.CafeLearningProgress zero =
                    new ProgressStore.CafeLearningProgress(0, 0);
            check("まだ出ない: clover_coaster", has(fresh, "clover_coaster"), false);
            for (int i = 0; i < 60; i++) {
                fresh.rewardQuiz("q-4", i, zero);
            }
            check("累計50問正解: clover_coaster", has(fresh, "clover_coaster"), true);

            // ── 9. 7日連続の履歴を持つ進捗ファイルは、起動時に解放される ─────
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
            check("再読み込みでも残る: first_try_tamper",
                    has(reloaded, "first_try_tamper"), true);

            // ── 10. 初回条件を逃しても、異なる問題の復習で一発記録を作り直せる ─
            Path replayFile = dir.resolve("replay.json");
            ProgressStore replay = new ProgressStore(replayFile);
            for (int i = 1; i <= 10; i++) {
                replay.recordAttempt("r-1#" + i);
                replay.recordAttempt("r-1#" + i);
                replay.markCleared("r-1#" + i);
            }
            check("初回を逃した状態: first_try_tamper",
                    has(replay, "first_try_tamper"), false);
            for (int i = 0; i < 20; i++) {
                replay.recordMasterySubmission("r-1#1", true);
            }
            check("同じ復習問題の連打では出ない: first_try_tamper",
                    has(replay, "first_try_tamper"), false);
            replay.recordMasterySubmission("r-1#1", false);
            for (int i = 1; i <= 9; i++) {
                replay.recordMasterySubmission("r-1#" + i, true);
            }
            check("復習9問では出ない: first_try_tamper",
                    has(replay, "first_try_tamper"), false);
            replay.flushNow();
            replay = new ProgressStore(replayFile);
            replay.recordMasterySubmission("r-1#10", true);
            check("再起動をまたぐ復習10問: first_try_tamper",
                    has(replay, "first_try_tamper"), true);

            // クリア後の提出も累計へ入り、10回になれば粘りのドリッパーを回収できる。
            check("復習前: persistence_dripper",
                    has(replay, "persistence_dripper"), false);
            for (int i = 0; i < 8; i++) {
                replay.recordAttempt("r-1#1");
            }
            check("クリア後も累計10回: persistence_dripper",
                    has(replay, "persistence_dripper"), true);

            // ── 11. 全クイズの初回答を間違えても、異なる20問の復習で回収できる ─
            Path quizReplayFile = dir.resolve("quiz-replay.json");
            ProgressStore quizReplay = new ProgressStore(quizReplayFile);
            for (int i = 0; i < 20; i++) {
                quizReplay.recordQuiz("qr-1", i, 1, false);
            }
            check("初回答を逃した状態: quiz_bell", has(quizReplay, "quiz_bell"), false);
            for (int i = 0; i < 25; i++) {
                quizReplay.recordQuiz("qr-1", 0, 0, true);
            }
            check("同じクイズの連打では出ない: quiz_bell",
                    has(quizReplay, "quiz_bell"), false);
            quizReplay.recordQuiz("qr-1", 0, 1, false);
            for (int i = 0; i < 19; i++) {
                quizReplay.recordQuiz("qr-1", i, 0, true);
            }
            check("復習19問では出ない: quiz_bell", has(quizReplay, "quiz_bell"), false);
            quizReplay.recordQuiz("qr-1", 19, 0, true);
            check("復習20問連続: quiz_bell", has(quizReplay, "quiz_bell"), true);

            // ── 12. 初クリア日がばらばらでも、同日復習と章の再制覇で回収できる ─
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
            check("初クリア日が分散: prep_pot", has(dated, "prep_pot"), false);
            dated.noteChapterAchievements(List.of("d-1#1", "d-1#2"));
            check("初クリア日が分散: one_sitting_bookmark",
                    has(dated, "one_sitting_bookmark"), false);
            for (int i = 1; i <= 15; i++) {
                dated.recordMasterySubmission("d-1#" + i, true);
            }
            check("同じ日に異なる15問を復習: prep_pot", has(dated, "prep_pot"), true);
            dated.noteChapterAchievements(List.of("d-1#1", "d-1#2"));
            check("同じ日に章を復習: one_sitting_bookmark",
                    has(dated, "one_sitting_bookmark"), true);

            // ヒントを使った章も、全問へ再正解すれば無傷の勲章を回収できる。
            hinted.recordMasterySubmission("z-1#1", true);
            hinted.recordMasterySubmission("z-1#2", true);
            hinted.noteChapterAchievements(List.of("z-1#1", "z-1#2"));
            check("ヒント使用後に章を復習: unscathed_medal",
                    has(hinted, "unscathed_medal"), true);

            // ── 13. カフェを育てず完走しても、節目型12アイテムを受け取れる ───
            Path catchUpFile = dir.resolve("catch-up.json");
            ProgressStore catchUp = new ProgressStore(catchUpFile);
            for (int i = 1; i <= 475; i++) {
                catchUp.markCleared("c-1#" + i);
            }
            check("未完走では記念品を贈らない",
                    catchUp.ensureCafeCompletionCatchUp(474, 475) == 0, true);
            int grantedItems = catchUp.ensureCafeCompletionCatchUp(475, 475);
            Set<String> milestoneIds = Set.of(
                    "lucky_coin", "golden_bean", "quiz_crown", "fortune_cat",
                    "fever_bell", "java_relic", "rhythm_recipe", "comeback_ticket",
                    "brand_charter", "quiz_festival_pass", "mastery_archive",
                    "lifelong_trophy");
            List<Map<String, Object>> milestoneItems = items(catchUp).stream()
                    .filter(item -> milestoneIds.contains(item.get("id")))
                    .toList();
            check("完走時に節目型アイテムを贈る", grantedItems == 12, true);
            check("節目型12アイテムを発見", milestoneItems.size() == 12, true);
            check("節目型12アイテムを所持",
                    milestoneItems.stream().allMatch(item -> Boolean.TRUE.equals(item.get("owned"))),
                    true);
            check("完走記念品は重複しない",
                    catchUp.ensureCafeCompletionCatchUp(475, 475) == 0, true);

            System.out.println("\nACHIEVEMENTS OK: 9種の初回・復習条件と完走時救済を確認しました");
        } finally {
            Files.deleteIfExists(dir.resolve("catch-up.json"));
            Files.deleteIfExists(dir.resolve("dated.json"));
            Files.deleteIfExists(dir.resolve("quiz-replay.json"));
            Files.deleteIfExists(dir.resolve("replay.json"));
            Files.deleteIfExists(dir.resolve("other.json"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
