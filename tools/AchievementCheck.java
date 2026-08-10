import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

            System.out.println("\nACHIEVEMENTS OK: 9種すべての解放条件を確認しました");
        } finally {
            Files.deleteIfExists(dir.resolve("other.json"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
