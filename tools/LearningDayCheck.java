import jq.progress.LearningDay;
import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 1日の区切り（午前4時）を確かめる。
 *
 * <p>区切りを動かすと、連続学習日数・復習の期限・その日の達成条件・獲得の履歴が
 * まとめてずれる。どれも「日付が変わった瞬間」しか出ない不具合なので、
 * <b>時計を動かさずに測れる形</b>にしてここで見張る ―
 * {@link LearningDay#of(LocalDateTime)} は時刻を引数で取るので、深夜の挙動を
 * その場で作れる。</p>
 *
 * <p>あわせて<b>画面へ渡す値の一致</b>も見る。画面は「今日ぶん」を自分で数えるので
 * （獲得の履歴・復習の途中セットの控え）、サーバと違う区切りを持つと
 * 同じ瞬間に「今日」が食い違う。</p>
 */
public final class LearningDayCheck {

    private static void eq(String label, Object actual, Object expected) {
        if (!String.valueOf(actual).equals(String.valueOf(expected))) {
            throw new IllegalStateException(
                    "NG " + label + ": expected=" + expected + " actual=" + actual);
        }
        System.out.println("OK  " + label + " (" + actual + ")");
    }

    private static void ok(String label, boolean cond) {
        if (!cond) {
            throw new IllegalStateException("NG " + label);
        }
        System.out.println("OK  " + label);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[区切りの当たり方（区切りは" + LearningDay.START_HOUR + "時）]");
        LocalDate day = LocalDate.of(2026, 8, 22);
        LocalDate before = day.minusDays(1);
        eq("0:00 は前日ぶん", LearningDay.of(day.atTime(0, 0)), before);
        eq("区切りの1分前は前日ぶん",
                LearningDay.of(day.atTime(LearningDay.START_HOUR, 0).minusMinutes(1)), before);
        eq("区切りちょうどはその日", LearningDay.of(day.atTime(LearningDay.START_HOUR, 0)), day);
        eq("昼はその日", LearningDay.of(day.atTime(12, 0)), day);
        eq("23:59 はその日", LearningDay.of(day.atTime(23, 59)), day);
        eq("月をまたぐ深夜も前日ぶん",
                LearningDay.of(LocalDate.of(2026, 9, 1).atTime(1, 30)), LocalDate.of(2026, 8, 31));
        // 4時ちょうどで切れているので、1日ぶんの長さは変わらない（どの時刻も1つの学習日に入る）
        eq("区切りの前後で1日ずれるだけ",
                LearningDay.of(day.atTime(LearningDay.START_HOUR, 0))
                        .toEpochDay() - LearningDay.of(day.atTime(LearningDay.START_HOUR, 0)
                        .minusMinutes(1)).toEpochDay(), 1L);

        System.out.println("\n[区切りの値そのもの]");
        ok("深夜に置いてある（0時より後、正午より前）",
                LearningDay.START_HOUR > 0 && LearningDay.START_HOUR < 12);
        eq("いまの学習日は of(now) と同じ", LearningDay.today(), LearningDay.of(LocalDateTime.now()));
        eq("記録に入れる形は YYYY-MM-DD", LearningDay.todayText(), LearningDay.today().toString());

        Path dir = Files.createTempDirectory("jq-learning-day-");
        Path file = dir.resolve("progress.json");
        try {
            System.out.println("\n[画面へ渡す値]");
            ProgressStore p = new ProgressStore(file);
            Map<?, ?> client = (Map<?, ?>) p.toClientJson(
                    new ProgressStore.CafeLearningProgress(0, 0));
            ok("dayStartHour を渡している", client.containsKey("dayStartHour"));
            eq("渡す値はサーバの区切りと同じ", client.get("dayStartHour"), LearningDay.START_HOUR);

            System.out.println("\n[記録に入る日付]");
            p.markCleared("d#1");
            p.flushNow();
            String saved = Files.readString(file);
            ok("クリア日は学習日で入る（深夜は前日ぶん）",
                    saved.contains("\"clearedAt\":\"" + LearningDay.todayText() + "\""));
            eq("その日にクリアした問題は連続1日目", p.streak(), 1);
            // 期限の起点は clearedAt（＝学習日）。ヒントなし・一発クリアは3日後から始まるので、
            // 「翌日が最初の期限」を見るには苦戦した形（提出2回）で作る
            p.recordAttempt("d#2");
            p.recordAttempt("d#2");
            p.markCleared("d#2");
            eq("初クリアの翌日が最初の期限（起点も学習日）", p.reviewDue("d#2").daysUntilDue(), 1);
            eq("ヒントなし一発クリアは3日後", p.reviewDue("d#1").daysUntilDue(), 3);

            // 深夜0〜3時台に走らせても、作り置きの日付とストアの「今日」が噛み合うこと。
            // ここが崩れると、日付を作る検査（達成条件・カフェ試算）が夜だけ落ちる
            ProgressStore seeded = seedStreak(dir.resolve("streak.json"), 7);
            eq("直近7日ぶんを置くと連続7日", seeded.streak(), 7);
        } finally {
            for (Path leftover : Files.list(dir).toList()) {
                Files.deleteIfExists(leftover);
            }
            Files.deleteIfExists(dir);
        }

        System.out.println("\n1日の区切りは午前" + LearningDay.START_HOUR + "時で揃っています。");
    }

    /** 学習日を基準に「今日までのN日」を書いた進捗ファイルを作る（AchievementCheck と同じ作り）。 */
    private static ProgressStore seedStreak(Path file, int days) throws Exception {
        StringBuilder dates = new StringBuilder();
        LocalDate first = LearningDay.today().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            dates.append(i == 0 ? "\"" : ",\"").append(first.plusDays(i)).append("\"");
        }
        Files.writeString(file, "{\"clearDates\":[" + dates + "]}");
        return new ProgressStore(file);
    }
}
