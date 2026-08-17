package jq.progress;

import java.util.Set;

/**
 * カフェが読む、学習の記録。
 *
 * <p>カフェ経済は学習の記録を<b>読むだけ</b>で、書くことはない（★・提出回数・ヒントの使用・
 * クリアした日を見て、売上と解放条件を決める）。この非対称性を型にしたのがこの窓である。
 * {@link CafeEconomy} は進捗の保存側を直接触れず、ここに並んだ問いだけを投げられる。</p>
 *
 * <p>答えるのは<b>事実だけ</b>にしてある。「無傷で何問続いたか」は学習側が数えるが、
 * 「何問続いたら解放か」はカフェ側が決める（{@code FLAWLESS_ITEM_RUN} などのしきい値は
 * {@link CafeEconomy} にある）。ここにしきい値を持ち込むと、カフェの調整のたびに
 * 学習側を触ることになる。</p>
 *
 * <p>実装は {@link ProgressStore} の内側にある。呼ばれるのは常に {@code ProgressStore} の
 * {@code synchronized} メソッドの中なので、実装側で追加の同期は要らない。</p>
 */
interface LearningRecord {

    /** ★の数（クリア済みの問題数）。アイテムの解放条件と店構えのレベルに使う。 */
    int clearedTaskCount();

    /** その問題のクリア記録（ヒント使用・提出回数・クリア日）。未クリアなら null。 */
    ProgressStore.Cleared cleared(String taskKey);

    /** その問題の提出回数。一度も出していなければ 0。 */
    int attempts(String taskKey);

    /** 今日を含む連続学習日数。今日も昨日も学習していなければ 0。 */
    int streakDays();

    /** これまでで最も長く続いた連続学習日数。いまの連続が途切れていても残る。 */
    int longestClearStreak();

    /** ヒントを使わず1回の提出で通した問題が、最も長く続いた数。 */
    int bestFlawlessRun();

    /**
     * クリア済みの問題1問へ費やした提出回数の最大。
     *
     * クリア時に記録した回数と、その後の解き直しを含む現在の提出回数の大きい方で数える。
     */
    int maxAttemptsOnAnyTask();

    /** 1日に初クリアした問題数の最大。 */
    int busiestDayClears();

    /** その日に初クリアした問題のキー。 */
    Set<String> clearedKeysOn(String day);

    /** クリア済みの問題のキー。復習で仕上げた割合を出すのに使う。 */
    Set<String> clearedKeys();
}
