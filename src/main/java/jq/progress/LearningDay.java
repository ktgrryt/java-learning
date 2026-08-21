package jq.progress;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学習の「1日」の区切り。<b>暦の0時ではなく午前4時で切る。</b>
 *
 * <p>0時で切ると、夜ふかしして 0:30 に解いた1問が翌日ぶんになる ―― 連続学習日数（🔥）は
 * 前日が空白のまま切れ、復習の期限も1日ずれ、獲得の履歴では寝る前と寝たあとで「今日」が
 * 分かれる。寝る前の学習が翌日へ飛ばないよう、区切りを深夜へ動かした
 * （2026-08-22・利用者の要望）。</p>
 *
 * <p><b>区切りは1つだけ。</b>連続日数・復習の期限・その日の達成条件（🍀 その日に15問・
 * 章の1日制覇・🍮 その日は失敗なし）・獲得の履歴が、すべてここを通る。日付を見る処理を
 * 増やすときも {@link #today()} を使う ―― {@code LocalDate.now()} を直に呼ぶと、そこだけ
 * 0時で切り替わって「今日」が食い違う（`tools/check-learning-day.sh` が見張っている）。</p>
 *
 * <p>保存する形は変えていない（日付は今までどおり {@code YYYY-MM-DD} の文字列）。区切りを
 * 動かしても、すでに記録された日付はそのまま読める。</p>
 *
 * <p>画面側にも同じ区切りが要る（獲得の履歴の「今日の獲得」と「今日 / 昨日」のラベル、
 * 復習の途中セットの控え）。数字を2か所に書くと片方だけ動くので、{@link #START_HOUR} を
 * {@code /api/state} の {@code progress.dayStartHour} で渡し、画面はそれを読む。</p>
 */
public final class LearningDay {

    /**
     * 1日の始まり（時）。{@code 0} にすると暦の日付と同じ挙動に戻る。
     *
     * <p>4時にしたのは、深夜0〜3時台の学習を前日ぶんとして数えるため。5時より遅くすると、
     * 早朝に始めた人のぶんが前日へ寄ってしまう。</p>
     */
    public static final int START_HOUR = 4;

    private LearningDay() {
    }

    /** いまの学習日。0:00〜3:59 は前日を返す。 */
    public static LocalDate today() {
        return of(LocalDateTime.now());
    }

    /** 記録に入れる形（{@code YYYY-MM-DD}）。 */
    public static String todayText() {
        return today().toString();
    }

    /**
     * その時刻が属する学習日。
     *
     * <p>時計を動かさずに確かめられるよう、引数を取る形も公開している（→ {@code LearningDayCheck}）。
     * 引き算は壁時計のまま行う（{@code LocalDateTime} なので、夏時間のある地域でも
     * 「その日の4時」で切れる）。</p>
     */
    public static LocalDate of(LocalDateTime at) {
        return at.minusHours(START_HOUR).toLocalDate();
    }
}
