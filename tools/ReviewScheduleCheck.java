import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;

/** 忘却曲線の期限と、細かくした苦手度の目盛りを確かめる。 */
public final class ReviewScheduleCheck {

    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    private static void eq(String label, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("NG " + label + ": expected=" + expected + " actual=" + actual);
        }
        System.out.println("OK  " + label + " (" + actual + ")");
    }

    private static void ok(String label, boolean cond) {
        if (!cond) { throw new IllegalStateException("NG " + label); }
        System.out.println("OK  " + label);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jq-review-sched-");
        Path file = dir.resolve("progress.json");
        try {
            ProgressStore p = new ProgressStore(file);

            System.out.println("\n[苦手度の目盛り]");
            p.markCleared("t#1");
            eq("初期は0", p.reviewWeight("t#1"), 0);
            p.recordMasterySubmission("t#1", false);
            eq("失敗1回で1単位（=0.25点）", p.reviewWeight("t#1"), 1);
            for (int i = 0; i < 3; i++) { p.recordMasterySubmission("t#1", false); }
            eq("4回失敗で従来の1点ぶん", p.reviewWeight("t#1"), 4);
            p.recordMasterySubmission("t#1", true);
            eq("正解で1点（4単位）下がる", p.reviewWeight("t#1"), 0);
            for (int i = 0; i < 200; i++) { p.recordMasterySubmission("t#1", false); }
            eq("上限は8点ぶん（32単位）", p.reviewWeight("t#1"), 32);

            System.out.println("\n[復習の期限]");
            ProgressStore q = new ProgressStore(dir.resolve("b.json"));
            q.markCleared("s#1");
            eq("初クリアの翌日が最初の期限", q.reviewDue("s#1").daysUntilDue(), 1);
            eq("レベル0から始まる", q.reviewDue("s#1").level(), 0);

            q.recordMasterySubmission("s#1", true);
            eq("すっと通れば次のレベルへ", q.reviewDue("s#1").level(), 1);
            eq("間隔は3日", q.reviewDue("s#1").daysUntilDue(), 3);
            q.recordMasterySubmission("s#1", true);
            eq("さらに次へ", q.reviewDue("s#1").level(), 2);
            eq("間隔は7日", q.reviewDue("s#1").daysUntilDue(), 7);

            // 同じ日に失敗してから通したら1つ戻す
            q.recordMasterySubmission("s#1", false);
            eq("失敗では期限を動かさない", q.reviewDue("s#1").daysUntilDue(), 7);
            q.recordMasterySubmission("s#1", true);
            eq("危なかったので1つ戻る", q.reviewDue("s#1").level(), 1);
            eq("間隔も3日へ戻る", q.reviewDue("s#1").daysUntilDue(), 3);

            // 通せないまま終わると期限切れのまま残る
            ProgressStore r = new ProgressStore(dir.resolve("c.json"));
            r.markCleared("u#1");
            r.flushNow();
            String json = Files.readString(dir.resolve("c.json"));
            json = json.replace("\"clearedAt\":\"" + java.time.LocalDate.now() + "\"",
                    "\"clearedAt\":\"" + java.time.LocalDate.now().minusDays(30) + "\"");
            Files.writeString(dir.resolve("c.json"), json);
            r = new ProgressStore(dir.resolve("c.json"));
            ok("30日前にクリアした問題は期限切れ", r.reviewDue("u#1").overdue());
            eq("29日ぶん過ぎている", r.reviewDue("u#1").daysUntilDue(), -29);
            for (int i = 0; i < 5; i++) { r.recordMasterySubmission("u#1", false); }
            ok("何度失敗しても期限切れのまま残る", r.reviewDue("u#1").overdue());
            eq("期限は動いていない", r.reviewDue("u#1").daysUntilDue(), -29);

            // 最上位まで進むと4か月ごと
            ProgressStore t = new ProgressStore(dir.resolve("d.json"));
            t.markCleared("v#1");
            for (int i = 0; i < 10; i++) { t.recordMasterySubmission("v#1", true); }
            eq("最上位レベルで止まる", t.reviewDue("v#1").level(), 6);
            eq("間隔は120日", t.reviewDue("v#1").daysUntilDue(), 120);

            // 保存して読み直しても続く
            t.flushNow();
            ProgressStore reloaded = new ProgressStore(dir.resolve("d.json"));
            eq("再読込でもレベルが残る", reloaded.reviewDue("v#1").level(), 6);
            eq("再読込でも期限が残る", reloaded.reviewDue("v#1").daysUntilDue(), 120);

            // 目盛りを細かくする前のファイルは4倍して読む
            ProgressStore old = new ProgressStore(dir.resolve("e.json"));
            old.markCleared("w#1");
            old.flushNow();
            String oldJson = Files.readString(dir.resolve("e.json"))
                    .replace("\"reviewWeightScale\":4", "\"reviewWeightScale\":1")
                    .replace("\"reviewWeight\":{}", "\"reviewWeight\":{\"w#1\":3}");
            Files.writeString(dir.resolve("e.json"), oldJson);
            ProgressStore migrated = new ProgressStore(dir.resolve("e.json"));
            eq("旧ファイルの3点は12単位へ換算", migrated.reviewWeight("w#1"), 12);

            System.out.println("\nREVIEW SCHEDULE OK: 期限と苦手度の目盛りを確認しました");
        } finally {
            for (String n : new String[] {"progress.json", "b.json", "c.json", "d.json", "e.json"}) {
                Files.deleteIfExists(dir.resolve(n));
            }
            Files.deleteIfExists(dir);
        }
    }
}
