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

            // ── 「もう理解した」で間隔をいちばん先まで飛ばす（2026-08-22）──────────
            //
            // 依頼「あまりにも簡単な問題が何度も出てくると面倒。正解したあと『もう理解したので
            // しばらく出さない』ボタンが欲しい」。動かすのは**次に出す日だけ**で、★・コイン・
            // 苦手度・一発正解の連続は触らない（触ると押すだけで得をする操作になる）。
            System.out.println("\n[もう理解した（先送り）]");
            ProgressStore e = new ProgressStore(dir.resolve("j.json"));
            ok("クリアしていない問題は飛ばせない", e.easeTaskReview("m#1") == null);
            e.recordAttempt("m#1");
            e.recordAttempt("m#1");
            e.markCleared("m#1");
            eq("いまの期限は翌日", e.reviewDue("m#1").daysUntilDue(), 1);
            int weightBefore = e.reviewWeight("m#1");
            eq("最上位まで飛ぶ", e.easeTaskReview("m#1").daysUntilDue(), 120);
            eq("レベルも最上位", e.reviewDue("m#1").level(), 6);
            eq("苦手度は動かない", e.reviewWeight("m#1"), weightBefore);
            // 予定が無かった問題を飛ばしたので、戻すと「まだ復習していない」状態へ帰る
            eq("押した直後は戻せる", e.undoEaseTaskReview("m#1").daysUntilDue(), 1);
            ok("2回目は戻せない（控えは1つだけ）", e.undoEaseTaskReview("m#1") == null);

            // すでに復習した問題では、戻すと元のレベルと日付に帰る
            e.recordMasterySubmission("m#1", true, REVIEW);
            eq("1回復習して3日後", e.reviewDue("m#1").daysUntilDue(), 3);
            eq("そこから飛ばすと120日後", e.easeTaskReview("m#1").daysUntilDue(), 120);
            eq("戻すと3日後へ帰る", e.undoEaseTaskReview("m#1").daysUntilDue(), 3);
            eq("一発正解の連続も元のまま", e.reviewDue("m#1").cleanRun(), 1);

            // 別の問題の「戻す」は受け付けない（鍵が違えば控えは使えない）
            e.markCleared("m#2");
            e.easeTaskReview("m#1");
            ok("別の問題では戻せない", e.undoEaseTaskReview("m#2") == null);
            eq("飛ばした側はそのまま", e.reviewDue("m#1").daysUntilDue(), 120);

            // クイズも同じ形。答えていないクイズは飛ばせない
            ok("答えていないクイズは飛ばせない",
                    e.easeQuizReview("m-1", 0, true) == null);
            e.recordQuiz("m-1", 0, 0, true, ZERO);
            ok("答えたクイズは期限切れから始まる", e.quizReviewDue("m-1", 0, true).overdue());
            eq("クイズも最上位まで飛ぶ",
                    e.easeQuizReview("m-1", 0, true).daysUntilDue(), 120);
            ok("戻すと期限切れへ帰る",
                    e.undoEaseQuizReview("m-1", 0, true).overdue());
            // 控えは問題とクイズで1つを共有する（最後に押した1件だけ戻せる）
            e.easeTaskReview("m#1");
            e.easeQuizReview("m-1", 0, true);
            ok("あとから押したクイズは戻せる",
                    e.undoEaseQuizReview("m-1", 0, true) != null);
            ok("先に押した問題はもう戻せない", e.undoEaseTaskReview("m#1") == null);

            // 飛ばした期限は保存される（読み直しても120日後のまま）
            e.easeTaskReview("m#2");
            e.flushNow();
            ProgressStore easedReloaded = new ProgressStore(dir.resolve("j.json"));
            eq("再読込でも飛ばした期限が残る",
                    easedReloaded.reviewDue("m#2").daysUntilDue(), 120);
            ok("再読込のあとは戻せない（控えは保存しない）",
                    easedReloaded.undoEaseTaskReview("m#2") == null);

            // ── 確認クイズの期限（2026-08-22）─────────────────────────────────
            //
            // クイズにも問題と同じ忘却曲線を持たせた。これが無かったころ「もう復習した」ことを
            // 覚えているのは📣の連続正解の集合だけで、1問間違えて集合が空に戻るたび、教材の
            // 先頭のクイズから出し直していた（利用者の指摘「同じような問題ばかり出ている」）。
            //
            // 第4引数は「いま記録に残っている回答が正解か」。復習は quizChoices を書き換えない
            // ので、復習の正誤とは別の値である。
            System.out.println("\n[確認クイズの期限]");
            ProgressStore qz = new ProgressStore(dir.resolve("q.json"));
            ok("まだ復習していないクイズは期限切れ扱い", qz.quizReviewDue("1-3", 0, true).overdue());
            eq("期限切れの日数は0（今日が期限）", qz.quizReviewDue("1-3", 0, true).daysUntilDue(), 0);

            // 1度目に正解しているクイズは1段上から始まるので、正解し直すと7日後
            qz.recordQuizReview("1-3", 0, true, true);
            eq("正解で1段進む", qz.quizReviewDue("1-3", 0, true).level(), 2);
            eq("次は7日後", qz.quizReviewDue("1-3", 0, true).daysUntilDue(), 7);
            ok("期限切れではなくなる", !qz.quizReviewDue("1-3", 0, true).overdue());
            qz.recordQuizReview("1-3", 0, true, true);
            eq("続けて正解すると14日後", qz.quizReviewDue("1-3", 0, true).daysUntilDue(), 14);

            // 1度目に間違えたまま残っているクイズは0から（正解し直しても3日後に戻ってくる）
            qz.recordQuizReview("1-3", 1, true, false);
            eq("誤答のままのクイズは3日後", qz.quizReviewDue("1-3", 1, false).daysUntilDue(), 3);

            // 間違えたら1段戻す。日付は今日へ動かすので、同じ日には出ない
            qz.recordQuizReview("1-3", 0, false, true);
            eq("不正解で1段戻る", qz.quizReviewDue("1-3", 0, true).level(), 2);
            eq("戻っても翌日以降（同じ日には出し直さない）",
                    qz.quizReviewDue("1-3", 0, true).daysUntilDue(), 7);
            for (int i = 0; i < 10; i++) { qz.recordQuizReview("1-3", 2, true, true); }
            eq("最上位で止まる", qz.quizReviewDue("1-3", 2, true).level(), 6);
            eq("間隔は120日", qz.quizReviewDue("1-3", 2, true).daysUntilDue(), 120);

            // 期限は「最後に復習した日 + 間隔」で引き直すので、保存して読み直しても続く
            qz.flushNow();
            ProgressStore qzReloaded = new ProgressStore(dir.resolve("q.json"));
            eq("再読込でもクイズのレベルが残る",
                    qzReloaded.quizReviewDue("1-3", 2, true).level(), 6);
            eq("再読込でも期限が残る",
                    qzReloaded.quizReviewDue("1-3", 2, true).daysUntilDue(), 120);
            // 30日前に復習した記録にすると、間隔7日のクイズは23日ぶん過ぎている
            String qzJson = Files.readString(dir.resolve("q.json"))
                    .replace("\"at\":\"" + jq.progress.LearningDay.today() + "\"",
                            "\"at\":\"" + jq.progress.LearningDay.today().minusDays(30) + "\"");
            Files.writeString(dir.resolve("q.json"), qzJson);
            ProgressStore qzOld = new ProgressStore(dir.resolve("q.json"));
            ok("時間が経てば期限切れに戻る", qzOld.quizReviewDue("1-3", 0, true).overdue());
            eq("23日ぶん過ぎている", qzOld.quizReviewDue("1-3", 0, true).daysUntilDue(), -23);
            qzOld.resetAll();
            ok("進捗リセットで期限も消える（また期限切れ扱い）",
                    qzOld.quizReviewDue("1-3", 0, true).overdue());

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
                    "\nREVIEW SCHEDULE OK: 期限（できている問題の飛び級を含む）・「もう理解した」の先送りと取り消し・確認クイズの期限・苦手度の目盛り・ブックマークを確認しました");
        } finally {
            deleteTree(dir);
        }
    }

    /** 一時ディレクトリをまるごと消す。中のファイル名を数え上げないのは、進捗の作りが
     *  増えたとき（控えが1つ増えるなど）に後始末だけが落ちるのを避けるため。 */
    private static void deleteTree(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 一時ディレクトリなので消し残っても構わない
                }
            });
        }
    }
}
