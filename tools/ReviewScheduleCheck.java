import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 忘却曲線の期限と、細かくした苦手度の目盛りを確かめる。
 *
 * <p>あわせてブックマークも見る。問題のブックマークとクイズのしおりは鍵の形が同じ
 * （{@code 5-2#1}）なので、別の集合で持てているかをここで見張る。</p>
 */
public final class ReviewScheduleCheck {

    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    /** 復習モードからの提出。間隔の飛び級はこれだけで数える（通常画面の再提出は1段のまま）。 */
    private static final boolean REVIEW = true;

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
            eq("失敗1回で2単位（=0.5点）", p.reviewWeight("t#1"), 2);
            p.recordMasterySubmission("t#1", false);
            eq("2回失敗で1点ぶん（4単位）", p.reviewWeight("t#1"), 4);
            p.recordMasterySubmission("t#1", true);
            eq("正解で1点（4単位）下がる＝失敗2回ぶん", p.reviewWeight("t#1"), 0);
            // 「試しに実行」はここを通らない（/api/run は記録を触らない）。もし通す変更が
            // 入ると、この目盛りでは試行錯誤だけで上限へ届いてしまう
            for (int i = 0; i < 3; i++) { p.recordMasterySubmission("t#1", false); }
            eq("3回失敗で1.5点ぶん（🔥苦手のしきい値）", p.reviewWeight("t#1"), 6);
            p.recordMasterySubmission("t#1", true);
            p.recordMasterySubmission("t#1", true);
            eq("正解2回で0まで戻る", p.reviewWeight("t#1"), 0);
            for (int i = 0; i < 200; i++) { p.recordMasterySubmission("t#1", false); }
            eq("上限は8点ぶん（32単位）", p.reviewWeight("t#1"), 32);

            System.out.println("\n[復習の期限]");
            // 苦戦してからクリアした問題（失敗した提出があるので attempts が2以上）
            ProgressStore q = new ProgressStore(dir.resolve("b.json"));
            q.recordAttempt("s#1");
            q.recordAttempt("s#1");
            q.markCleared("s#1");
            eq("苦戦した問題は初クリアの翌日が最初の期限", q.reviewDue("s#1").daysUntilDue(), 1);
            eq("レベル0から始まる", q.reviewDue("s#1").level(), 0);

            q.recordMasterySubmission("s#1", true, REVIEW);
            eq("すっと通れば次のレベルへ", q.reviewDue("s#1").level(), 1);
            eq("間隔は3日", q.reviewDue("s#1").daysUntilDue(), 3);
            eq("一発正解1回では飛ばさない", q.reviewDue("s#1").cleanRun(), 1);
            ok("まだ飛び級ではない", !q.reviewDue("s#1").onFastTrack());

            // ── 一発正解が2連続したら2段まとめて進める（2026-08-19）──────────
            q.recordMasterySubmission("s#1", true, REVIEW);
            ok("2連続で飛び級に入る", q.reviewDue("s#1").onFastTrack());
            eq("1→3へ2段飛ばす", q.reviewDue("s#1").level(), 3);
            eq("間隔は14日", q.reviewDue("s#1").daysUntilDue(), 14);
            q.recordMasterySubmission("s#1", true, REVIEW);
            eq("続くあいだは2段ずつ", q.reviewDue("s#1").level(), 5);
            eq("間隔は60日", q.reviewDue("s#1").daysUntilDue(), 60);

            // 同じ日に失敗してから通したら1つ戻し、連続も切る
            q.recordMasterySubmission("s#1", false, REVIEW);
            eq("失敗では期限を動かさない", q.reviewDue("s#1").daysUntilDue(), 60);
            eq("失敗した時点で連続は切れる", q.reviewDue("s#1").cleanRun(), 0);
            q.recordMasterySubmission("s#1", true, REVIEW);
            eq("危なかったので1つ戻る", q.reviewDue("s#1").level(), 4);
            eq("間隔も30日へ戻る", q.reviewDue("s#1").daysUntilDue(), 30);
            ok("戻ったあとは飛び級から外れる", !q.reviewDue("s#1").onFastTrack());
            q.recordMasterySubmission("s#1", true, REVIEW);
            eq("数え直しの1回目は1段だけ", q.reviewDue("s#1").level(), 5);

            // ── 通常画面の再提出では飛び級しない ────────────────────────────
            // クリアした自分の解答が最初から入っているので、通っても「思い出せた」ことにならない。
            // ここで数えてしまうと、復習を1度もせずに間隔だけ伸ばせてしまう。
            ProgressStore n = new ProgressStore(dir.resolve("i.json"));
            n.recordAttempt("x#1");
            n.recordAttempt("x#1");
            n.markCleared("x#1");
            n.recordMasterySubmission("x#1", true);
            n.recordMasterySubmission("x#1", true);
            eq("通常画面で2回通しても1段ずつ", n.reviewDue("x#1").level(), 2);
            eq("一発正解の連続は増えない", n.reviewDue("x#1").cleanRun(), 0);
            ok("飛び級には入らない", !n.reviewDue("x#1").onFastTrack());
            n.recordMasterySubmission("x#1", true, REVIEW);
            eq("復習で通した1回目は1段", n.reviewDue("x#1").level(), 3);
            n.recordMasterySubmission("x#1", true, REVIEW);
            eq("復習で2連続してはじめて2段", n.reviewDue("x#1").level(), 5);

            // ── ヒントなし・1回の提出でクリアした問題は3日後から ──────────────
            ProgressStore f = new ProgressStore(dir.resolve("g.json"));
            f.recordAttempt("z#1");
            f.markCleared("z#1");
            eq("すっとクリアした問題は3日後が最初の期限", f.reviewDue("z#1").daysUntilDue(), 3);
            eq("レベル1から始まる", f.reviewDue("z#1").level(), 1);
            f.recordMasterySubmission("z#1", true, REVIEW);
            eq("そこから1段", f.reviewDue("z#1").level(), 2);
            f.recordMasterySubmission("z#1", true, REVIEW);
            eq("2連続で2段飛ばして最上位のひとつ下", f.reviewDue("z#1").level(), 4);

            ProgressStore h = new ProgressStore(dir.resolve("h.json"));
            h.recordAttempt("y#1");
            h.revealHint("y#1", 0);
            h.markCleared("y#1");
            eq("ヒントを見た問題は翌日から", h.reviewDue("y#1").daysUntilDue(), 1);

            // 通せないまま終わると期限切れのまま残る
            ProgressStore r = new ProgressStore(dir.resolve("c.json"));
            // 苦戦してクリアした問題にする（最初の期限が翌日 = レベル0）。
            // すっとクリアした問題は3日後から始まるので、過ぎた日数の期待値が変わる
            r.recordAttempt("u#1");
            r.recordAttempt("u#1");
            r.markCleared("u#1");
            r.flushNow();
            String json = Files.readString(dir.resolve("c.json"));
            json = json.replace("\"clearedAt\":\"" + jq.progress.LearningDay.today() + "\"",
                    "\"clearedAt\":\"" + jq.progress.LearningDay.today().minusDays(30) + "\"");
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
            for (int i = 0; i < 10; i++) { t.recordMasterySubmission("v#1", true, REVIEW); }
            eq("最上位レベルで止まる", t.reviewDue("v#1").level(), 6);
            eq("間隔は120日", t.reviewDue("v#1").daysUntilDue(), 120);

            // 保存して読み直しても続く（一発正解の連続も残る）
            t.flushNow();
            ProgressStore reloaded = new ProgressStore(dir.resolve("d.json"));
            eq("再読込でもレベルが残る", reloaded.reviewDue("v#1").level(), 6);
            eq("再読込でも期限が残る", reloaded.reviewDue("v#1").daysUntilDue(), 120);
            ok("再読込でも飛び級のままでいる", reloaded.reviewDue("v#1").onFastTrack());

            // clean を持たない古いファイルは0から数え直す（いきなり間隔は飛ばない）
            String oldPlan = Files.readString(dir.resolve("d.json"))
                    .replaceAll(",\"clean\":\\d+", "");
            Files.writeString(dir.resolve("d.json"), oldPlan);
            ProgressStore legacy = new ProgressStore(dir.resolve("d.json"));
            eq("古いファイルは連続0から", legacy.reviewDue("v#1").cleanRun(), 0);
            ok("読み直した時点では飛び級ではない", !legacy.reviewDue("v#1").onFastTrack());

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

            // ── ブックマーク（問題のブックマークとクイズのしおりは別物）──────────
            //
            // 鍵の形が同じ（"5-2#1"）なので、同じ集合で持つと「問題1」と「クイズ2問目」が
            // 同一視される。取り違えると復習の出題一覧に居ないものが混ざるので、
            // 別物であることをここで見張る。
            System.out.println("\n[ブックマーク]");
            ProgressStore b = new ProgressStore(dir.resolve("f.json"));
            ok("最初は問題に付いていない", !b.isBookmarked("5-2#1"));
            ok("最初はクイズにも付いていない", !b.isQuizBookmarked("5-2", 1));

            ok("問題へ付けられる", b.toggleBookmark("5-2#1"));
            ok("クイズは付かないまま", !b.isQuizBookmarked("5-2", 1));
            ok("クイズへ付けられる", b.toggleQuizBookmark("5-2", 1));
            ok("同じ番号でも問題とクイズは別物", b.isBookmarked("5-2#1") && b.isQuizBookmarked("5-2", 1));
            ok("押し直すと外れる", !b.toggleQuizBookmark("5-2", 1));
            ok("外したのはクイズだけ", b.isBookmarked("5-2#1") && !b.isQuizBookmarked("5-2", 1));

            b.toggleQuizBookmark("5-2", 1);
            b.toggleQuizBookmark("12-3", 0);
            b.flushNow();
            ProgressStore marks = new ProgressStore(dir.resolve("f.json"));
            ok("再読込でもクイズのしおりが残る",
                    marks.isQuizBookmarked("5-2", 1) && marks.isQuizBookmarked("12-3", 0));
            ok("付けていないクイズは残らない", !marks.isQuizBookmarked("5-2", 0));
            marks.resetAll();
            ok("進捗リセットでクイズのしおりも消える", !marks.isQuizBookmarked("5-2", 1));

            System.out.println(
                    "\nREVIEW SCHEDULE OK: 期限（できている問題の飛び級を含む）・苦手度の目盛り・ブックマークを確認しました");
        } finally {
            for (String n : new String[] {
                    "progress.json", "b.json", "c.json", "d.json", "e.json", "f.json",
                    "g.json", "h.json", "i.json"}) {
                Files.deleteIfExists(dir.resolve(n));
            }
            Files.deleteIfExists(dir);
        }
    }
}
