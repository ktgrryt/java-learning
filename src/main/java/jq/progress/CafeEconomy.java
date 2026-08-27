package jq.progress;

import jq.content.Lesson;
import jq.json.MiniJson;
import jq.progress.ProgressStore.AutomationPurchaseResult;
import jq.progress.ProgressStore.CafeAutomation;
import jq.progress.ProgressStore.CafeAward;
import jq.progress.ProgressStore.CafeInvestment;
import jq.progress.ProgressStore.CafeItem;
import jq.progress.ProgressStore.CafeItemEffect;
import jq.progress.ProgressStore.CafeLearningProgress;
import jq.progress.ProgressStore.CafeUpgrade;
import jq.progress.ProgressStore.Cleared;
import jq.progress.ProgressStore.ExpansionResult;
import jq.progress.ProgressStore.InvestmentPurchaseResult;
import jq.progress.ProgressStore.ItemPurchaseResult;
import jq.progress.ProgressStore.PassiveSalesResult;
import jq.progress.ProgressStore.PurchaseResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Java Café の経済。売上・設備・アイテム・自動営業・店舗網・終盤投資の状態と規則を持つ。
 *
 * <p>学習の記録（★・提出回数・ヒントの使用・クリアした日）は {@link LearningRecord} 越しに
 * <b>読むだけ</b>で、書き換えることはない。依存はこの一方向だけなので、報酬や価格を直したいときに
 * 進捗の永続化を読む必要がなく、逆に進捗の保存を直すときにカフェを読む必要もない。</p>
 *
 * <p>「何が存在するか」（設備・アイテムの一覧と効果）は {@link CafeCatalog} にある。
 * こちらは「いくらで買えるか」「どれだけ育つか」という<b>調整つまみ</b>を持つ ―
 * 経済のバランスを触るときはこのファイルの定数を見る（{@code EXPANSION_CUBIC_COST}、
 * {@code ENDGAME_INVESTMENT_BASE_COST} など）。値を変えたら
 * {@code CAFE_ECONOMY_VERSION} を1つ進め、{@code tools/simulate-cafe.sh} を読む。</p>
 *
 * <p><b>同期はしない。</b>このクラスのメソッドは {@link ProgressStore} の
 * {@code synchronized} メソッドの中からのみ呼ばれる（呼び出しの直列化は
 * {@code ProgressStore} が受け持つ）。ここで独自に錠を持つと、錠が2つになって
 * 順序の取り決めが増える。</p>
 *
 * <p>保存も自分では行わない。状態を変えたら {@link Saver} へ知らせるだけで、
 * いつ書き出すかは {@code ProgressStore} が決める（{@code dirty} の管理を1箇所に保つため）。</p>
 */
final class CafeEconomy {

    /**
     * 保存の予約。{@code ProgressStore#saveSoon()} と {@code #saveEventually()} へつながる。
     *
     * <p>{@link #soon()} は★や購入のように失うと痛い変更、{@link #eventually()} は
     * 自動売上のtickのように「失っても次の機会に作り直せる」変更に使う。</p>
     */
    interface Saver {
        void soon();

        void eventually();
    }

    private final LearningRecord learningRecord;
    private final Saver saver;

    CafeEconomy(LearningRecord learningRecord, Saver saver) {
        this.learningRecord = learningRecord;
        this.saver = saver;
    }

    // 33: 複数ファイルのlabを3問足した（基礎編のパッケージ境界・モジュール、実践編のロケール）。
    //     必須問題が680→683へ増え、1問ぶんの報酬が積み上がるほうが先に効いて投資率が
    //     23.73%（下限25%割れ）へ落ちたので、終盤改装の基準額を 3,000億→4,200億 へ上げた。
    //     ★683では改装は9段目のままなので跳ねる側は動かず、上限側は余裕がある
    //     （plain 27.04% / ラッキーコイン未解放 36.48%）。傾き（1.2倍）は触っていない。
    // 32: 📣ひらめきメガホンの解放を **12問連続 → 10問連続** へ緩めた（依頼「入手条件が
    //     結構難しい」）。1セット3問なので、必要なセット数が5→4へ1つ減る。31と同じ理由で
    //     初回答の連続も同じ数にしてある（片方だけ緩めるとカードに数字が2つ並ぶ）。
    //     売上は動かない ―― tools/simulate-cafe.sh の3筋書きで生涯コインも投資率も無変化。
    //     📣を買える250,000コインが貯まるのは解放よりずっと後なので、解放が2問早まっても
    //     購入時期は動かない（31のときと同じ理由）。
    // 31: 📣ひらめきメガホンの解放を **20問連続 → 12問連続** へ緩めた（依頼）。あわせて
    //     クイズにも問題と同じ忘却曲線の期限を入れ、正解したクイズはしばらく復習に出さない
    //     ようにしている（ProgressStore の quizPlans）。**出る本数が減るので条件も緩める**、
    //     という組で1つの変更である。緩めたのは復習の道だけでなく「1度目の回答での連続」も
    //     同じ数 ―― 片方だけ緩めるとアイテムのカードに数字が2つ並ぶ。
    //     売上は動かない ―― tools/simulate-cafe.sh の3筋書き（plain / reviewer / unlucky）
    //     すべてで生涯コインも投資率も**1コインも変わらなかった**。📣を買える250,000コインが
    //     貯まるのは解放よりずっと後なので、解放が8問早まっても購入時期が動かない。
    // 30: 復習の1セットに手応えを持たせた（依頼「復習は新規の学習と同じくらい大事なので、
    //     復習にメリットが生まれるようにしてほしい」）。触ったのは3つ。
    //     (a) 期限が来た問題の報酬を **30%→50%**（REVIEW_REWARD_PERCENT）。
    //     (b) **期限前の「早めの復習」にも払う**（REVIEW_EARLY_REWARD_PERCENT = 12%）。
    //         ただし1日に払うのは REVIEW_EARLY_REWARD_TASKS_PER_DAY 問まで ―
    //         期限が上限を作らない側なので、上限は日ごとの本数で作る。これで
    //         **復習ホームを開いた回は必ず実入りになる**（期限切れが0の日でも0コインに
    //         ならない）。29版では期限前は0コインで、定常状態ではセット4問のうち
    //         1問しか払われず「1セット＝新問0.3問ぶん」だった。
    //     (c) 復習手当系統を前寄せで強くした（+4…+100% → +8…+88%）。上限は
    //         50% × 1.88 = **94%** で、初クリアを超えない不変条件は保っている。
    //     系統の価格はRank8以降を引き上げてある（復習で増えたコインの行き先。
    //     reviewer の投資率が下限10%を割らないための吸い込みでもある）。
    // 29: 復習にコインを払うようにした（依頼「復習でもコインがもらえるようにしたい」）。
    //     払うのは**期限が来た問題を復習で通したときだけ**で、額は1問クリアの30%
    //     （REVIEW_REWARD_PERCENT）。通した時点で期限が翌日以降へ動くので、同じ問題を
    //     連打しても2回目以降は0コイン ― 上限が仕組みとして決まる（「クリア済みは何度でも
    //     解き直せるから払えない」という以前の前提は、忘却曲線の期限が入って変わった）。
    //     期限前の「早めの復習」は0コインのままにしてある。
    //     **常連サービス系統（今日の1杯目）と皆勤の日めくりを外し、その枠へ差し替えた。**
    //     系統は12段すべてを0%にしても reviewer の生涯コインが 119,219,558,695,422 →
    //     ...207（-215コイン）しか動かない、実質何もしていない系統だった（クイズ接客を
    //     同じ手で測ると plain が -30.6%）。価格12段はそのまま復習手当系統が引き継ぐので、
    //     plain（復習しない筋書き）の支出と収入はどちらも変わらない。
    // 28: コンボスタンプ帳を7倍→5倍へ下げ、下げたぶんをラッキーコインの大当たりへ移した
    //     （依頼「コンボが強すぎるのでラッキーを強くして少し合わせたい」）。ほぼ同じ価格
    //     （80,000と77,777）なのに、生涯コインへの効きは +27.5% と +3.7% で7倍以上離れて
    //     いた ― 0.3%抽選という一番重い条件の1枚が一番弱かった。5%×2→**5%×5**、
    //     5問ごと7倍→**5倍**で、効きは +16.3% と +10.7%（1.5倍差）になる。
    //     **総額はほぼ動かない**（plain 27.95%→27.84%、reviewer 14.15%→14.10%）ので、
    //     店舗・設備・改装の価格と基準額は触っていない。抽選に外れたままの人は生涯コインが
    //     -5.9%、投資率 28.99%→30.81% と上限側へ寄るが、必須の設備・店舗・改装は全て買える。
    //     **確率ではなく倍率を上げている。** 当たる回は cafeRewardSequence だけで決まる
    //     （利用者ごとの乱数を持たない）ので、確率を上げると章制覇×4.5やコンボが乗った
    //     大きい回を拾いやすく、同じ期待倍率1.20でも 10%×3 は投資率22.06%（下限割れ）、
    //     5%×5 は25.82%だった。下限25%に収まる生涯コインの天井は約66.8兆。
    // 27: 確認クイズを復習の対象へ入れた（依頼「取り返しのつかない要素は無くしたい」）。
    //     📣ひらめきメガホンは「1度目の回答で20問連続正解」だけが条件で、初回答は永久に
    //     消費されるため、全522問を答え終えて20連続が無ければ**二度と取れなかった**。
    //     復習で解き直したクイズの連続正解（quizMasteryRun）でも解放するようにして、
    //     取り逃しを無くした。🏆生涯学習トロフィーと同じ「初回 or 復習」の形である。
    //     売上は動かない ― 復習のクイズはチップを払わず、quizChoices も書き換えないので
    //     ★も正解数も動かない。
    // 26: ラッキーコインの解放抽選を1%から0.3%へ下げた（依頼）。抽選の枠を100→1000へ
    //     増やして0.1%刻みを表せるようにしてある（isLuckyValue の buckets）。
    //     **584問すべて正解しても解放されない人が約17%になる**（1%のときは約0.3%）ので、
    //     「未解放でも破綻しない」ことを見る tools/simulate-cafe.sh の unlucky が
    //     例外ではなく普通の筋書きになった。投資率そのものは動かない（解放が遅れるほど
    //     生涯売上は減るが、plain は 27.95%→27.95%、未解放は 28.99% で据え置き）。
    // 25: 確認クイズのチップを「1度目の回答で正解したとき」だけに絞った。誤答のあとに
    //     表示された正解を押しても入らない（クイズが読んで押すだけの入金口になっていた）。
    //     ひらめきメガホンの解放も初回答の20問連続だけにし、答え直し込みの連続記録
    //     （quizMasteryRun）は消した。tools/simulate-cafe.sh は全問を1度目で正解する
    //     筋書きなので試算は動かない。動くのは「取り逃した人の生涯売上」だけで、
    //     クイズは1問売上の2%なので投資率への影響は上限でも1%未満。
    // 24: 終盤改装の値上がりを1.5倍→1.2倍へ寝かせ、基準額を2,000億→3,000億へ上げた。
    //     1.5倍でも★680（9段目の解放直後）で44.15%まで来ており、次の★700で57.5%、
    //     ★740では107.5%（買えない額）になる計算だった。費用が等比で伸びるのに売上は
    //     問題数に比例して増えるだけなので、傾きが急だといつか必ず追い越される。
    //     1.2倍なら 29.0%→31.7%→38.8% と3回先の解放まで帯に留まる。傾きを寝かせると
    //     吸い込みが弱まって下限25%へ寄るため、基準額で戻している。
    // 23: 問題を574問から596問へ増やしたぶん投資率が下限へ寄っていたので、終盤改装の
    //     基準額を450億→2,000億へ上げ、あわせて1段ごとの値上がりを2倍→1.5倍へ寝かせた。
    //     2倍のままでは、次の1段だけで全段の合計と同額になり、20問ごとの解放の瞬間に
    //     ラッキーコイン未解放の投資率が上限45%を超えてしまう（基準額を上げるほど跳ね幅も
    //     比例して大きくなるため、基準額だけでは直せない）。plain 35.34%→38.35%。
    // 22: 序盤の解放ペースを緩めるため、Rank1〜5の価格を引き上げた（Rank1は約6倍、
    //     Rank5は約1.27倍）。数問で6系統すべてのRank1が買えていたのを、1〜2節で1つ、
    //     1章クリアで3つに落としてある。Rank6以降と出店費は据え置きなので、
    //     完走時の投資率は 41.21%→41.23%（plain）でほぼ動かない。
    // 21: ラッキーコインの解放を、★・累計売上条件から「問題正解ごとに1%抽選」へ変更した。
    //     574問すべて外れても投資率45%以内になるよう、終盤改装の基準額を450億へ下げた。
    // 20: ラッキーコインを「頻繁な小当たり」から「5%の大当たり」へ変更し、価格を77,777にした。
    //     期待売上が下がるぶん、全購入時の投資率を範囲内へ戻すため終盤改装の基準額も下げた。
    private static final int CAFE_ECONOMY_VERSION = 33;
    private static final int CUP_PRICE = 500;
    private static final int MAX_CAFE_STORES = 512;
    private static final long FIRST_EXPANSION_COST = 2_500L;
    /**
     * 5店舗以降の出店費（規模の三乗に掛ける係数）。終盤の主なコイン消費先。
     *
     * <p><b>問題数を増やしたら {@code tools/simulate-cafe.sh} を通すこと。</b>
     * 生涯売上はブランド倍率（下の定数）が設備効果へ掛かるぶん、問題数に対して
     * 加速して伸びる。一方で購入費の合計は問題数と無関係なので、教材を増やすだけで
     * 投資率（購入費 ÷ 生涯売上）が下がり、目標の25〜45%を割る。
     * 現在は20問ごとの任意改装が追加分を吸収する。必須の店舗網の進行そのものに
     * 不具合があるときだけ、この係数を調整する。</p>
     *
     * <p>実測: 全509問で8,500だと22.9%、全516問で15,000だと27.7%、
     * 全532問で25,000だと25.92%、全547問で38,000だと25.12%だった。
     * 全574問では38,000だと18.26%まで落ち、57,000へ上げて25.35%に戻した。
     * <b>確認クイズを増やしても動く</b>（クイズのチップも収入で、購入の時期がずれると
     * 生涯売上が階段状に変わる）。524→533問の途中では24.17%まで落ちたが、
     * 535問へそろえた時点では29.50%だった ― 係数は57,000のままでよい。
     * <b>途中の数字で係数を動かさず、content を直し終えてから測る。</b></p>
     */
    private static final long EXPANSION_CUBIC_COST = 57_000L;
    /** 完成した章の問題1問あたりのブランド成長。全574問で約x10.76になる。 */
    private static final int BRAND_GROWTH_BASIS_POINTS_PER_TASK = 170;
    /**
     * 復習で再正解した問題1問あたりのブランド成長。
     *
     * <p>復習の<b>コインは期限が来た問題にだけ</b>払う（{@link #rewardReview}）。
     * こちらの倍率は期限に関係なく育つが、対象は {@code cafeMasteryTasks} ―
     * 「復習で正解した重複しない問題」の集合なので、1問につき1回しか数えない。
     * 上限は問題数で構造的に決まる。</p>
     *
     * <p>復習ノートを持つと4倍になるため、1問あたりの上限は 40 * 4 = 160。
     * <b>初回クリアの170を超えないこと</b>が不変条件で、
     * {@code tools/simulate-cafe.sh} がここを検査する。順序を崩すと
     * 「新しい問題を解くより復習した方が儲かる」状態になる。</p>
     */
    private static final int REVIEW_BRAND_GROWTH_BASIS_POINTS_PER_TASK = 40;
    /**
     * 復習で戻せる自動売上の枠（問題数ぶん）の上限。
     *
     * 枠を戻さないままアプリを閉じて溜め込めないようにする。回収そのものは
     * レート（最上位でも5%/分）で律速されるので、1問分でも回収には20分かかる。
     */
    private static final int MAX_REVIEW_PASSIVE_CREDITS = 5;
    private static final int TASK_COMBO_INTERVAL = 5;
    /**
     * 取得の重い2アイテムの条件。
     *
     * 12種のうちこの2つだけは、学習量ではなく「やり込み」で解放する。
     * 復習ノートは全問の3分の1以上を復習したとき、生涯学習トロフィーは
     * ヒントなし・一発で25問続けたときに初めて現れる。
     */
    private static final int REVIEW_MASTERY_ITEM_TASKS = 200;
    private static final int FLAWLESS_ITEM_RUN = 25;
    /** 粘りのドリッパーが「粘った」とみなす提出回数。 */
    private static final int RETRY_BONUS_ATTEMPTS = 5;
    /** 粘りのドリッパーそのものが解放される提出回数（1問への累計）。 */
    private static final int RETRY_ACHIEVEMENT_ATTEMPTS = 10;
    /**
     * 📣ひらめきメガホンが解放される連続正解の数。
     *
     * <p>「1度目の回答で連続正解」と「復習で異なるクイズへ連続正解」の<b>どちらでも</b>この数。
     * 初回答は答えるたび永久に減る在庫なので、片方だけでは取り逃しが起きる。</p>
     *
     * <p>20から12へ緩めた（2026-08-22・利用者の指示）。クイズにも忘却曲線の期限を入れて
     * <b>正解したクイズはしばらく出さない</b>ようにしたので、復習で出るクイズの本数が減った
     * ―― 1セット3問だった以前と同じ20問では、期限を待つあいだ連続が伸びなくなる。
     * さらに12から10へ緩めた（2026-08-26・利用者の指示「入手条件が結構難しい」）。
     * 1セット3問なので、10問なら<b>4セット</b>で届く（12問では5セット必要だった）。</p>
     */
    private static final int QUIZ_STREAK_ITEM_RUN = 10;
    /**
     * 初回・復習を問わず、問題へ正解したときにラッキーコインを引く確率（<b>千分率</b>）。
     *
     * <p>3 = 0.3%。1%から下げたときに枠を100→1000へ増やした ― {@code isLuckyValue} は
     * 混ぜた値を枠数で割った余りで判定するので、%のままでは0.3を表せない。</p>
     *
     * <p><b>1周する間に当たらない人が約17%いる</b>（1%では約0.3%だった）。ただし抽選は
     * 復習の正解でも引ける（{@link #noteReviewSubmission} は初回・復習を同じ1回として数える）
     * ので、完走後も引き続けられる ―― <b>取り逃しにはならない</b>。
     * 未解放のまま完走しても必須設備・店舗・終盤投資を買えることは
     * {@code tools/simulate-cafe.sh} の unlucky が見ている。</p>
     */
    private static final int LUCKY_COIN_UNLOCK_CHANCE_PER_MILLE = 3;
    /**
     * 期限が来た問題を復習で通したときに払う、1問クリアの売上に対する割合（%）。
     *
     * <p><b>払うのは期限が来た問題を通したときだけ</b>（判定は
     * {@code ProgressStore.recordMasterySubmission}）。通した時点で次の期限が
     * 翌日以降へ動くので、同じ問題を続けて通しても2回目からは0コインになる ―
     * 上限は「期限が来る問題の数」で構造的に決まり、定常状態では1日あたり数問である。
     * 期限前の「早めの復習」は{@link #REVIEW_EARLY_REWARD_PERCENT}へ分けてある。</p>
     */
    private static final int REVIEW_REWARD_PERCENT = 50;
    /**
     * 期限が来ていない問題を復習で通したときに払う割合（1問クリアの売上に対する%）。
     *
     * <p><b>期限が上限を作らない側なので、上限は日ごとの本数で作る</b>
     * （{@link #REVIEW_EARLY_REWARD_TASKS_PER_DAY}）。期限は「1問につき1日1回」を
     * 保証するが、期限前の問題は何問でも並べられるので、割合だけを決めると
     * 「1日に全問まわして稼ぐ」が成立してしまう。</p>
     *
     * <p>0にしないのは、<b>復習ホームを開いた回が必ず実入りになる</b>ようにするため。
     * 定常状態で期限が来るのは1日数問なので、セット4問のうち3問が「早めの復習」になる ―
     * そこが0コインだと、1セット通しても新問0.3問ぶんしか入らなかった（2026-08-22）。
     * 期限が来た問題（{@link #REVIEW_REWARD_PERCENT}）より必ず小さくしておくこと。
     * 逆転すると「期限を待たずに回すのが一番得」になり、忘却曲線の意味が消える。</p>
     */
    private static final int REVIEW_EARLY_REWARD_PERCENT = 12;
    /**
     * 「早めの復習」に払うのは、1日にこの本数まで。
     *
     * <p><b>同じ問題は1日1回しか数えない</b>（{@link #cafeReviewPaidTasks}）ので、
     * 「6問」は6つの異なる問題ぶんである。日付が変わると0に戻る ―
     * 区切りは午前4時（{@link LearningDay}）。</p>
     *
     * <p>6問＝1セット半ぶん。設備を最上位まで積んでも
     * 6 × 12% × 1.88 = 1問クリアの約1.35問ぶんが1日の上限で、
     * 新しい問題を解くほうが常に得なままである。</p>
     */
    private static final int REVIEW_EARLY_REWARD_TASKS_PER_DAY = 6;
    /**
     * 復習1問で払える上限（1問クリアの売上に対する割合・%）。
     *
     * <p>復習手当系統は最上位で +88% なので 50→94% までしか伸びず、ここには当たらない。
     * <b>将来この値をいじったときの歯止め</b>として置いてある ―
     * 100%に届くと「新しい問題を解くより復習した方が儲かる」状態になる。
     * 比べるのは<b>素の金額どうし</b>である（ラッキーコインと生涯学習トロフィーは
     * trigger を問わず乗るので、初回クリアにも同じように乗る）。
     * {@code tools/check-review-economy.sh} がここを検査する。</p>
     */
    private static final int REVIEW_REWARD_MAX_PERCENT = 96;
    /**
     * 自動売上は、次に★を取るまで現在の問題報酬5問分まで。
     * 最上位設備でも上限まで100分かかり、オフライン中は増えない。
     */
    private static final int PASSIVE_CASH_CAP_BASIS_POINTS = 50_000;
    /** 終盤の任意投資は★520から20問ごとに1段階ずつ解放する。 */
    private static final int ENDGAME_INVESTMENT_START_STARS = 500;
    private static final int ENDGAME_INVESTMENT_STAR_INTERVAL = 20;
    /**
     * 収益効果のない任意投資。追加章のコイン余りを受け止める調整弁。
     *
     * <p><b>効くのは傾き（1段ごとの値上がり）で、基準額ではない。</b>費用は等比で伸びるのに
     * 売上は問題数に比例して増えるだけなので、傾きが急だと必ずどこかの解放で追い越される。
     * 実際に2倍→1.5倍へ寝かせてもまだ急で、★680（9段目の解放直後）で44.15%、
     * <b>次の★700では57.5%、★740では107.5%（買えない額）</b>になる計算だった。
     * 1.2倍まで寝かせると 29.0% → 31.7% → 38.8% と、3回先の解放まで帯に留まる。</p>
     *
     * <p>基準額は下限側の調整に使う。傾きを寝かせるとコインの吸い込みが弱くなり、
     * こんどは下限25%へ寄るので、2,000億→3,000億へ上げて戻している。
     * 上げすぎると最初の1段（★520）だけが重くなるので、傾きで直せない分だけにする。</p>
     *
     * <p>2026-08-27に 3,000億→4,200億 へ上げた。複数ファイルのlabを3問足して★が680→683へ増え、
     * <b>1問ぶんの報酬が積み上がるほうが先に効いて</b>投資率が 23.73%（下限25%割れ）へ落ちたため。
     * ★683では9段目のままなので跳ねる側は動かず、上限側は余裕がある
     * （実測: plain 27.04%、ラッキーコイン未解放 36.48%）。</p>
     *
     * <p>触ったら {@code tools/simulate-cafe.sh} を通すこと。plainより先に、
     * ラッキーコイン未解放（生涯売上が最も小さい）の投資率が上限45%へ当たる。</p>
     */
    private static final long ENDGAME_INVESTMENT_BASE_COST = 420_000_000_000L;
    private static final long ENDGAME_INVESTMENT_STEP_NUMERATOR = 6L;
    private static final long ENDGAME_INVESTMENT_STEP_DENOMINATOR = 5L;
    /*
     * 設備（通常設備・自動営業）に★の解放条件は<b>置かない</b>。
     *
     * 以前はRankごとに必要★を決めていたが、それは「今このRankしか買えない」という
     * 一本道になり、どの系統へ先に投資するかという選択そのものを奪っていた。
     * 代わりに、上のRankほど価格が急に上がること（Rank1〜5は1段ごとに約4.2倍、
     * Rank6〜7は約5倍、Rank8以降は約6.5〜7.5倍。効果の伸びは1段あたり4〜7割なので、
     * 上のRankは1コインあたりの価値が下がる）を歯止めにする。
     * 手が届く範囲では常に「浅く広く買うか、1系統を深く買うか」を選べる。
     *
     * <b>Rank1の価格は「序盤の解放ペース」そのもの。</b> 1問の初クリアは500コイン
     * （店構えLv1で1杯 × {@link #CUP_PRICE}）から始まり、1節はおおむね2問なので、
     * 最も安いRank1（正解チップ1,800）が1〜2節クリアでちょうど1つ買える額になる。
     * ここを下げると、数問解いた時点で6系統すべてのRank1が同時に買えてしまい、
     * 「少しずつできることが増える」感触が最初の章で消える。Rank1〜5だけを
     * 引き上げてRank6以降を据え置いてあるのは、終盤の投資率（購入費÷生涯売上）が
     * Rank11〜12と出店費でほぼ決まり、序盤の数万コインでは動かないため。
     * 序盤ペースは {@code tools/simulate-cafe.sh} が検査する。
     *
     * 残っている★条件は設備ではない ― 店構えLv（{@link CafeCatalog#LEVELS}）、店舗の出店枠
     * （STORE_UNLOCK_STARS）、終盤改装（ENDGAME_INVESTMENT_START_STARS）、
     * アイテムの発見条件。どれも一本道なので、選択の幅を狭めない。
     */
    /** ★の進行に応じて段階的に広がる店舗上限。 */
    private static final int[] STORE_UNLOCK_STARS =
            {4, 22, 57, 101, 144, 187, 230, 270, 310, 345, 385, 425, 458, 483, 502};
    private static final int[] STORE_LIMITS =
            {2, 3, 5, 8, 12, 18, 27, 41, 62, 93, 140, 210, 315, 473, MAX_CAFE_STORES};
    /** ブラウザのタイマー停止を「放置中の売上」として誤加算しないための1回あたり上限。 */
    private static final long MAX_PASSIVE_TICK_MILLIS = 10_000L;
    /** カフェで現在使える売上。設備を買うと減る。 */
    private long cafeCash;
    /** これまでに提供したコーヒー。減らない成長指標。 */
    private long cafeCups;
    /** これまでに獲得したコイン。支出しても減らず、スペシャルアイテムの発見条件になる。 */
    private long cafeLifetimeCash;
    /** 報酬を受け取った回数。再起動によるラッキー判定の引き直しを防ぐ。 */
    private long cafeRewardSequence;
    /** ラッキーコイン解放抽選を利用者ごとに変える種。進捗リセット時だけ作り直す。 */
    private long cafeLuckyCoinUnlockSeed = ThreadLocalRandom.current().nextLong();
    /** 正解後にラッキーコイン解放抽選を行った回数。再起動による引き直しを防ぐ。 */
    private long cafeLuckyCoinUnlockDrawCount;
    /** 問題クリア報酬を受け取った回数。コンボ報酬の進行を保存する。 */
    private long cafeTaskRewardCount;
    /** 最後に★を獲得してから受け取った自動売上。上限をリロードで引き直さないため保存する。 */
    private long cafePassiveCashSinceTask;
    /**
     * 復習で戻したが、まだ自動売上の枠へ反映していない問題数。
     *
     * 枠の計算には「完成した章の問題数」（教材側しか知らない）が要るので、
     * 復習した時点では数えるだけにして、次に自動売上を集めるときへ持ち越す。
     */
    private int cafeReviewPassiveCredits;
    /** 現在営業している店舗数。出店するたび、全店ぶんの注文を同時に受ける。 */
    private int cafeStores = 1;
    /** ★520以降の任意の改装・社会貢献プロジェクトを完了した段階。 */
    private int cafeInvestmentLevel;
    /** 購入済み設備ID。 */
    private final Set<String> cafeUpgrades = new LinkedHashSet<>();
    /** 購入済みの自動営業設備ID。最上位の1台だけが稼働する。 */
    private final Set<String> cafeAutomationUpgrades = new LinkedHashSet<>();
    /** 所持しているスペシャルアイテムID。 */
    private final Set<String> cafeItems = new LinkedHashSet<>();
    /** アイテム画面で確認済みのスペシャルアイテムID。新発見の通知を再表示しないため保存する。 */
    private final Set<String> cafeSeenItems = new LinkedHashSet<>();
    /** 達成済みのアイテム解放条件。いちど達成したら、あとで崩れても外さない。 */
    private final Set<String> cafeAchievements = new LinkedHashSet<>();
    /** 確認クイズに1度目の回答で連続正解している数。間違えると0へ戻る（答え直しは数えない）。 */
    private int cafeQuizFirstStreak;
    /** 復習で連続正解した、重複しない問題。失敗すると空に戻る。 */
    private final Set<String> cafeMasteryTaskRun = new LinkedHashSet<>();
    /**
     * 復習で連続正解した、重複しないクイズ。間違えると空に戻る。
     *
     * <p><b>数えるのは復習として出し直した回答だけ</b>（{@code /api/quiz} の
     * {@code review}）。レッスン画面での答え直しは数えない ― 誤答すると正解の記号が
     * 表示されるので、それを押すだけで並んでしまう。同じクイズを何度解いても
     * 集合なので1回きり。</p>
     */
    private final Set<String> cafeQuizMasteryRun = new LinkedHashSet<>();
    /** 復習で再正解した問題。章をもう一度仕上げたかの判定と、ブランド成長に使う。 */
    private final Set<String> cafeMasteryTasks = new LinkedHashSet<>();
    /** 復習問題を最後に正解した日。日が変わったら当日分を空にする。 */
    private String cafeMasteryDay = "";
    /** {@link #cafeMasteryDay} に復習で正解した、重複しない問題。 */
    private final Set<String> cafeMasteryDayTasks = new LinkedHashSet<>();
    /**
     * 復習のコインを最後に払った日。日が変わると下の2つが空に戻る。
     *
     * <p>{@link #cafeMasteryDay} と分けてあるのは、あちらが「復習で正解した問題」
     * （払ったかどうかに関わらず入る）なのに対し、こちらは<b>払った問題</b>だから。
     * 同じ集合にすると、期限前で本数を使い切った日の問題まで「払った」に数えてしまう。</p>
     */
    private String cafeReviewPaidDay = "";
    /**
     * {@link #cafeReviewPaidDay} にコインを払った問題（期限ぶん・早めぶんの両方）。
     *
     * <p><b>同じ問題からは1日1回しかコインが入らない</b>という上限をここが持つ。
     * 期限ぶんは「通すと期限が翌日へ動く」ことで自然にそうなるが、期限前の
     * 「早めの復習」は何度でも通せるので、集合で押さえないと同じ1問を連打して
     * 本数ぶん稼げてしまう（2026-08-22）。</p>
     */
    private final Set<String> cafeReviewPaidTasks = new LinkedHashSet<>();
    /** {@link #cafeReviewPaidDay} に「早めの復習」へ払った本数。 */
    private int cafeReviewEarlyPaid;
    /** チップを払い終えたクイズ。二重払いを防ぐ。 */
    private final Set<String> rewardedQuizzes = new LinkedHashSet<>();
    /** 章制覇ボーナスを受け取った章。同時提出でも重複獲得させない。 */
    private final Set<String> rewardedChapters = new LinkedHashSet<>();
    /** 自動売上の画面セッション。永続化せず、再起動・オフライン中は一切加算しない。 */
    private String cafePassiveSessionId;
    private long cafePassiveLastTickNanos;
    /** ratePerMinute * elapsedMillis を60秒で割った端数。 */
    private long cafePassiveRemainder;

    // ------------------------------------------------- 学習側から届くできごと

    /**
     * クイズに答えた。1度目の回答だけを数え、正解ならチップを払う。
     *
     * <p><b>2度目以降の回答では何も動かない。</b>答え直し自体は歓迎する（覚え直す場が
     * 要る）が、不正解のフィードバックは正解の記号を出すので、答え直しに払うと
     * クイズが「表示された正解を押すだけの入金口」になってしまう。</p>
     *
     * <p>連続記録は1度目の回答で必ずどちらかへ動く（伸びるか0へ戻る）ので、
     * 変わったかは返さない。呼び出し側は回答そのものを保存するため常に保存する。</p>
     *
     * @param firstAnswer そのクイズに初めて答えたか
     * @return この回で払ったチップ。2度目以降の回答と不正解では {@link CafeAward#NONE}
     */
    CafeAward noteQuizAnswered(String quizKey, boolean correct, boolean firstAnswer,
                              CafeLearningProgress learning) {
        if (!firstAnswer) {
            return CafeAward.NONE;
        }
        cafeQuizFirstStreak = correct ? cafeQuizFirstStreak + 1 : 0;
        refreshCafeAchievements();
        return correct ? rewardQuiz(quizKey, learning) : CafeAward.NONE;
    }

    /**
     * 復習として出し直したクイズへの回答が届いた。<b>コインは1枚も払わない。</b>
     *
     * <p>数えるのは「異なるクイズへ連続して正解した数」だけで、これが
     * {@link #QUIZ_STREAK_ITEM_RUN} に届くと📣ひらめきメガホンが解放される。
     * 同じクイズを何度解き直しても集合なので1回きり ― でないと、覚えた1問を
     * 繰り返すだけで並んでしまう。間違えたら連続は空に戻る。</p>
     *
     * <p><b>復習の問題（{@link #rewardReview}）と違ってチップを払わない。</b>
     * 問題は忘却曲線の期限が「1問につき1日1回まで」という上限を作るが、クイズに期限は無く、
     * しかも選択肢が並んでいて答えが見えている ― 払うと押すだけの入金口になってしまう。
     * ★と正解数も動かさない ― 呼び出し側は {@code quizChoices} を書き換えないので、
     * 復習で間違えても★を失わない。</p>
     *
     * @return 記録が変わったら true（保存の予約に使う）
     */
    boolean noteQuizReviewAnswered(String quizKey, boolean correct) {
        if (!correct) {
            if (cafeQuizMasteryRun.isEmpty()) {
                return false;
            }
            cafeQuizMasteryRun.clear();
            return true;
        }
        if (!cafeQuizMasteryRun.add(quizKey)) {
            return false;
        }
        refreshCafeAchievements();
        return true;
    }

    /**
     * 復習の提出が届いた。倍率と自動売上の枠を動かす。
     *
     * <p><b>コインを払うのはここではない。</b> 期限が来た問題だけへ払うので、
     * 期限の記録を持つ {@code ProgressStore} が判定して {@link #rewardReview} を呼ぶ。
     * こちらが動かす倍率と枠はどちらも1問につき1回しか数えないため、
     * 上限は問題数で構造的に決まる。</p>
     *
     * @param alreadyCleared すでに★が付いている問題か（初クリアの提出では false）
     * @return 何か変わったら true
     */
    boolean noteReviewSubmission(String taskKey, boolean passed, boolean alreadyCleared) {
        boolean changed = false;
        if (passed) {
            // 初クリア前にも呼ばれる共通経路なので、通常問題と復習問題を同じ1回として抽選できる。
            changed |= drawLuckyCoinUnlock();
        }
        if (!passed || !alreadyCleared) {
            // 復習の合間に未クリア問題で失敗した場合も「連続正解」ではなくなる。
            if (!passed && !cafeMasteryTaskRun.isEmpty()) {
                cafeMasteryTaskRun.clear();
                changed = true;
            }
            return changed;
        }

        String today = LearningDay.todayText();
        if (!today.equals(cafeMasteryDay)) {
            cafeMasteryDay = today;
            cafeMasteryDayTasks.clear();
        }
        cafeMasteryTaskRun.add(taskKey);
        // ブランド倍率が数えるのはこの集合なので、同じ問題を何度解き直しても1回きり
        cafeMasteryTasks.add(taskKey);
        cafeMasteryDayTasks.add(taskKey);
        // 自動売上の枠だけは毎回1問分戻す。完走後は★が増えないので、ここが自動営業設備を
        // 動かし続ける唯一の入口になる。何度復習しても枠は既定の5問分より広がらず、
        // 回収はレート（最上位でも5%/分）で律速されるので、1問分でも20分かかる。
        cafeReviewPassiveCredits =
                Math.min(MAX_REVIEW_PASSIVE_CREDITS, cafeReviewPassiveCredits + 1);
        refreshCafeAchievements();
        return true;
    }

    // ------------------------------------------------------------ 読み書き

    /** 数値として読めれば0以上で返し、読めなければ既定値。壊れたファイルでも落とさないため。 */
    private static long longOf(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? Math.max(0L, n.longValue()) : fallback;
    }

    /** 進捗ファイルの {@code cafe} を読む。 */
    void loadFrom(Map<String, Object> root) {
        Map<String, Object> cafe = MiniJson.obj(root, "cafe");
        cafeCash = longOf(cafe, "cash", 0);
        cafeCups = longOf(cafe, "cups", 0);
        cafeLifetimeCash = cafe.containsKey("lifetimeCash")
                ? longOf(cafe, "lifetimeCash", cafeCash)
                : Math.max(cafeCash, saturatedMultiply(cafeCups, CUP_PRICE));
        cafeRewardSequence = longOf(cafe, "rewardSequence", 0);
        cafeLuckyCoinUnlockSeed = longOf(
                cafe, "luckyCoinUnlockSeed", cafeLuckyCoinUnlockSeed);
        cafeLuckyCoinUnlockDrawCount = Math.max(
                0L, longOf(cafe, "luckyCoinUnlockDrawCount", 0));
        cafeTaskRewardCount = longOf(cafe, "taskRewardCount", learningRecord.clearedTaskCount());
        cafePassiveCashSinceTask = longOf(cafe, "passiveCashSinceTask", 0);
        cafeReviewPassiveCredits = Math.min(MAX_REVIEW_PASSIVE_CREDITS,
                Math.max(0, MiniJson.intOf(cafe, "reviewPassiveCredits", 0)));
        cafeStores = Math.min(MAX_CAFE_STORES,
                Math.max(1, MiniJson.intOf(cafe, "storeCount", 1)));
        cafeInvestmentLevel = Math.min(1_000,
                Math.max(0, MiniJson.intOf(cafe, "investmentLevel", 0)));
        int economyVersion = MiniJson.intOf(cafe, "economyVersion", 1);
        if (economyVersion < 2) {
            // 初版は1杯ほぼ10円だったため、平均500円の新レートへ換算する。
            cafeCash *= 50L;
        }
        for (Object o : MiniJson.list(cafe, "ownedUpgrades")) {
            if (o instanceof String s && CafeCatalog.isKnownUpgrade(s)) {
                cafeUpgrades.add(s);
            }
        }
        for (Object o : MiniJson.list(cafe, "ownedAutomation")) {
            if (o instanceof String s && CafeCatalog.isKnownAutomation(s)) {
                cafeAutomationUpgrades.add(s);
            }
        }
        for (Object o : MiniJson.list(cafe, "ownedItems")) {
            if (o instanceof String s && CafeCatalog.isKnownItem(s)) {
                cafeItems.add(s);
            }
        }
        for (Object o : MiniJson.list(cafe, "seenItems")) {
            if (o instanceof String s && CafeCatalog.isKnownItem(s)) {
                cafeSeenItems.add(s);
            }
        }
        for (Object o : MiniJson.list(cafe, "achievements")) {
            if (o instanceof String s && CafeCatalog.ACHIEVEMENT_NOTES.containsKey(s)) {
                cafeAchievements.add(s);
            }
        }
        cafeQuizFirstStreak = MiniJson.intOf(cafe, "quizFirstStreak", 0);
        for (Object o : MiniJson.list(cafe, "masteryTaskRun")) {
            if (o instanceof String s) {
                cafeMasteryTaskRun.add(ProgressStore.migrateKey(s));
            }
        }
        for (Object o : MiniJson.list(cafe, "quizMasteryRun")) {
            if (o instanceof String s) {
                cafeQuizMasteryRun.add(ProgressStore.migrateKey(s));
            }
        }
        for (Object o : MiniJson.list(cafe, "masteryTasks")) {
            if (o instanceof String s) {
                cafeMasteryTasks.add(ProgressStore.migrateKey(s));
            }
        }
        cafeReviewPaidDay = MiniJson.str(cafe, "reviewPaidDay", "");
        for (Object o : MiniJson.list(cafe, "reviewPaidTasks")) {
            if (o instanceof String s2) {
                cafeReviewPaidTasks.add(ProgressStore.migrateKey(s2));
            }
        }
        cafeReviewEarlyPaid = Math.max(0, MiniJson.intOf(cafe, "reviewEarlyPaid", 0));
        cafeMasteryDay = MiniJson.str(cafe, "masteryDay", "");
        for (Object o : MiniJson.list(cafe, "masteryDayTasks")) {
            if (o instanceof String s) {
                cafeMasteryDayTasks.add(ProgressStore.migrateKey(s));
            }
        }
        // quizMasteryRun（答え直し込みの連続正解）は経済25で廃止した。古い記録は読み捨てる。
        for (Object o : MiniJson.list(cafe, "rewardedQuizzes")) {
            if (o instanceof String s) {
                rewardedQuizzes.add(s);
            }
        }
        for (Object o : MiniJson.list(cafe, "rewardedChapters")) {
            if (o instanceof String s) {
                rewardedChapters.add(s);
            }
        }
    }

    /**
     * カフェ機能追加前から学んでいた人の★を無かったことにしない。
     *
     * 過去のケース数は進捗ファイルだけでは分からないため、控えめな固定報酬で移行する。
     */
    void migrateFromLearning() {
        cafeCash = learningRecord.clearedTaskCount() * 12L * CUP_PRICE;
        cafeCups = learningRecord.clearedTaskCount() * 12L;
        cafeLifetimeCash = cafeCash;
        cafeTaskRewardCount = learningRecord.clearedTaskCount();
    }

    /** 進捗ファイルへ書き出す {@code cafe} の中身。 */
    Map<String, Object> toJson() {
        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("economyVersion", CAFE_ECONOMY_VERSION);
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("lifetimeCash", cafeLifetimeCash);
        cafe.put("rewardSequence", cafeRewardSequence);
        cafe.put("luckyCoinUnlockSeed", cafeLuckyCoinUnlockSeed);
        cafe.put("luckyCoinUnlockDrawCount", cafeLuckyCoinUnlockDrawCount);
        cafe.put("taskRewardCount", cafeTaskRewardCount);
        cafe.put("passiveCashSinceTask", cafePassiveCashSinceTask);
        cafe.put("reviewPassiveCredits", cafeReviewPassiveCredits);
        cafe.put("storeCount", cafeStores);
        cafe.put("investmentLevel", cafeInvestmentLevel);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("ownedAutomation", new ArrayList<>(cafeAutomationUpgrades));
        cafe.put("ownedItems", new ArrayList<>(cafeItems));
        cafe.put("seenItems", new ArrayList<>(cafeSeenItems));
        cafe.put("achievements", new ArrayList<>(cafeAchievements));
        cafe.put("quizFirstStreak", cafeQuizFirstStreak);
        cafe.put("masteryTaskRun", new ArrayList<>(cafeMasteryTaskRun));
        cafe.put("quizMasteryRun", new ArrayList<>(cafeQuizMasteryRun));
        cafe.put("masteryTasks", new ArrayList<>(cafeMasteryTasks));
        cafe.put("reviewPaidDay", cafeReviewPaidDay);
        cafe.put("reviewPaidTasks", new ArrayList<>(cafeReviewPaidTasks));
        cafe.put("reviewEarlyPaid", cafeReviewEarlyPaid);
        cafe.put("masteryDay", cafeMasteryDay);
        cafe.put("masteryDayTasks", new ArrayList<>(cafeMasteryDayTasks));
        cafe.put("rewardedQuizzes", new ArrayList<>(rewardedQuizzes));
        cafe.put("rewardedChapters", new ArrayList<>(rewardedChapters));
        return cafe;
    }

    /** 状態を初期値へ戻す（進捗リセットと、壊れたファイルからの復旧で使う）。 */
    void reset() {
        cafeCash = 0;
        cafeCups = 0;
        cafeLifetimeCash = 0;
        cafeRewardSequence = 0;
        cafeLuckyCoinUnlockSeed = ThreadLocalRandom.current().nextLong();
        cafeLuckyCoinUnlockDrawCount = 0;
        cafeTaskRewardCount = 0;
        cafePassiveCashSinceTask = 0;
        cafeReviewPassiveCredits = 0;
        cafeStores = 1;
        cafeInvestmentLevel = 0;
        cafeUpgrades.clear();
        cafeAutomationUpgrades.clear();
        cafeItems.clear();
        cafeSeenItems.clear();
        cafeAchievements.clear();
        cafeQuizFirstStreak = 0;
        cafeMasteryTaskRun.clear();
        cafeQuizMasteryRun.clear();
        cafeMasteryTasks.clear();
        cafeMasteryDay = "";
        cafeMasteryDayTasks.clear();
        cafeReviewPaidDay = "";
        cafeReviewPaidTasks.clear();
        cafeReviewEarlyPaid = 0;
        rewardedQuizzes.clear();
        rewardedChapters.clear();
        cafePassiveSessionId = null;
        cafePassiveLastTickNanos = 0;
        cafePassiveRemainder = 0;
    }

    Object toClientJson(CafeLearningProgress learning) {
        CafeCatalog.CafeLevel level = CafeCatalog.levelFor(learningRecord.clearedTaskCount());
        CafeCatalog.CafeLevel nextLevel = level.level() < CafeCatalog.LEVELS.size()
                ? CafeCatalog.LEVELS.get(level.level())
                : null;
        long orderCups = cupsPerNetworkOrderWithUpgrades();
        long brandMultiplierBasisPoints = cafeBrandMultiplierBasisPoints(learning);
        boolean maximumNetwork = cafeStores >= MAX_CAFE_STORES;
        int progressStoreLimit = currentCafeStoreLimit();
        boolean canExpandNetwork = !maximumNetwork && cafeStores < progressStoreLimit;
        Map<String, Object> cafe = new LinkedHashMap<>();
        cafe.put("cash", cafeCash);
        cafe.put("cups", cafeCups);
        cafe.put("lifetimeCash", cafeLifetimeCash);
        cafe.put("taskRewardCount", cafeTaskRewardCount);
        cafe.put("cupPrice", CUP_PRICE);
        cafe.put("level", level.level());
        cafe.put("levelTitle", level.title());
        cafe.put("levelThreshold", level.threshold());
        cafe.put("nextLevelStars", nextLevel == null ? null : nextLevel.threshold());
        cafe.put("cupsPerOrder", level.cupsPerOrder());
        cafe.put("orderCups", orderCups);
        long nextOrderCash = cafeCashForCups(orderCups, learning);
        cafe.put("nextOrderCash", nextOrderCash);
        cafe.put("passiveCashPerMinute", cafePassiveCashPerMinute(learning));
        long passiveCap = cafePassiveCashCap(learning);
        cafe.put("passiveCashCap", passiveCap);
        // 復習で戻した枠は次のtickで反映されるが、表示だけは先に差し引いて見せる
        long passiveSpent = Math.max(0L, cafePassiveCashSinceTask
                - saturatedMultiply(nextOrderCash, cafeReviewPassiveCredits));
        cafe.put("passiveCashRemaining", Math.max(0L, passiveCap - passiveSpent));
        CafeAutomation activeAutomation = currentCafeAutomation();
        cafe.put("passiveRateBasisPoints", activeAutomation == null
                ? 0 : activeAutomation.rateBasisPointsPerMinute());
        // 全報酬へ掛かるのは販売戦略だけ。復習手当は reviewBonusPercent が持つ
        cafe.put("bonusPercent", cafeSalesBonusPercent());
        cafe.put("salesBonusPercent", cafeSalesBonusPercent());
        cafe.put("reviewBonusPercent", cafeReviewBonusPercent());
        cafe.put("reviewRewardPercent", REVIEW_REWARD_PERCENT);
        cafe.put("reviewEarlyRewardPercent", REVIEW_EARLY_REWARD_PERCENT);
        cafe.put("reviewEarlyRewardPerDay", REVIEW_EARLY_REWARD_TASKS_PER_DAY);
        cafe.put("reviewEarlyRewardLeft", reviewEarlyRewardLeft());
        cafe.put("extraCups", cafeExtraCups());
        cafe.put("chapterBonusPercent", cafeChapterBonusPercent());
        cafe.put("quizTipPercent", cafeQuizTipPercent());
        cafe.put("clearedChapters", learning.clearedChapters());
        cafe.put("masteredChapterTasks", learning.masteredChapterTasks());
        cafe.put("brandMultiplierBasisPoints", brandMultiplierBasisPoints);
        cafe.put("reviewBrandBasisPoints", cafeReviewBrandBasisPoints());
        cafe.put("reviewedTasks", cafeMasteryTasks.size());
        // 📣の進み具合。画面はしきい値と「いまいくつ」だけを使う（帯とアイテムのカード）。
        // 連続に入っているクイズの鍵は渡さない ―― 復習の出題から外すのに使っていたが、
        // クイズに期限が入ってからは不要で、渡すと「期限が来ても出ないクイズ」を作れてしまう
        cafe.put("quizStreakGoal", QUIZ_STREAK_ITEM_RUN);
        cafe.put("quizFirstStreak", cafeQuizFirstStreak);
        cafe.put("quizReviewRun", cafeQuizMasteryRun.size());
        cafe.put("reviewedTaskPercent", reviewedTaskPercent());
        cafe.put("equipmentDiscountPercent", equipmentDiscountPercent());
        cafe.put("storeCount", cafeStores);
        cafe.put("maxStores", MAX_CAFE_STORES);
        cafe.put("storeLimit", Math.max(cafeStores, progressStoreLimit));
        cafe.put("nextStoreUnlockStars", canExpandNetwork || maximumNetwork
                ? null : nextCafeStoreUnlockStars());
        int nextStoreGain = canExpandNetwork ? nextCafeStoreGain(progressStoreLimit) : 0;
        cafe.put("nextStoreGain", nextStoreGain);
        cafe.put("nextStoreCount", canExpandNetwork ? cafeStores + nextStoreGain : null);
        cafe.put("expansionCost", canExpandNetwork ? nextCafeExpansionCost() : null);
        cafe.put("investmentLevel", cafeInvestmentLevel);
        cafe.put("investmentAvailableLevel", currentCafeInvestmentAvailableLevel());
        CafeInvestment nextInvestment = nextCafeInvestment();
        cafe.put("endgameInvestment", cafeInvestmentVisible() && nextInvestment != null
                ? cafeInvestmentToClientJson(nextInvestment)
                : null);
        cafe.put("ownedUpgrades", new ArrayList<>(cafeUpgrades));
        cafe.put("ownedAutomation", new ArrayList<>(cafeAutomationUpgrades));
        cafe.put("ownedItems", new ArrayList<>(cafeItems));

        List<Object> upgrades = new ArrayList<>();
        for (CafeUpgrade u : CafeCatalog.UPGRADES) {
            CafeUpgrade equipped = currentCafeUpgrade(u.effectType());
            int nextTier = equipped == null ? 1 : equipped.tier() + 1;
            long effectiveCost = cafeUpgradeCost(u);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.id());
            item.put("name", u.name());
            item.put("emoji", u.emoji());
            item.put("description", u.description());
            item.put("cost", effectiveCost);
            item.put("baseCost", u.cost());
            item.put("discounted", effectiveCost < u.cost());
            item.put("tier", u.tier());
            item.put("effectType", u.effectType());
            item.put("effectValue", u.effectValue());
            item.put("owned", cafeUpgrades.contains(u.id()));
            item.put("equipped", equipped != null && equipped.id().equals(u.id()));
            item.put("available", u.tier() == nextTier);
            upgrades.add(item);
        }
        cafe.put("upgrades", upgrades);

        List<Object> automation = new ArrayList<>();
        int nextAutomationTier = activeAutomation == null ? 1 : activeAutomation.tier() + 1;
        for (CafeAutomation item : CafeCatalog.AUTOMATION) {
            long effectiveCost = cafeAutomationCost(item);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("name", item.name());
            value.put("emoji", item.emoji());
            value.put("description", item.description());
            value.put("cost", effectiveCost);
            value.put("baseCost", item.cost());
            value.put("discounted", effectiveCost < item.cost());
            value.put("tier", item.tier());
            value.put("rateBasisPointsPerMinute", item.rateBasisPointsPerMinute());
            value.put("owned", cafeAutomationUpgrades.contains(item.id()));
            value.put("equipped", activeAutomation != null && activeAutomation.id().equals(item.id()));
            value.put("available", item.tier() == nextAutomationTier);
            automation.add(value);
        }
        cafe.put("automation", automation);

        List<Object> items = new ArrayList<>();
        int unseenItemCount = 0;
        for (CafeItem item : CafeCatalog.ITEMS) {
            boolean owned = cafeItems.contains(item.id());
            boolean discovered = owned || isCafeItemDiscovered(item);
            if (!discovered) {
                continue;
            }
            boolean unseen = !cafeSeenItems.contains(item.id());
            if (unseen) {
                unseenItemCount++;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("name", item.name());
            value.put("emoji", item.emoji());
            value.put("description", item.description());
            value.put("cost", item.cost());
            value.put("discovered", true);
            value.put("unseen", unseen);
            value.put("owned", owned);
            List<Object> effects = new ArrayList<>();
            for (CafeItemEffect effect : item.effects()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", effect.type());
                e.put("value", effect.value());
                effects.add(e);
            }
            value.put("effects", effects);
            value.put("unlockNote", item.byAchievement()
                    ? CafeCatalog.ACHIEVEMENT_NOTES.getOrDefault(item.unlockAchievement(), "")
                    : "");
            items.add(value);
        }
        cafe.put("items", items);
        cafe.put("unseenItemCount", unseenItemCount);
        return cafe;
    }

    /**
     * 初クリアした注文の報酬。客単価は売上へ掛ける。
     *
     * {@code taskKey} は、ヒントを使ったか・何回で通ったかを見るアイテム
     * （一発仕上げのタンパー、粘りのドリッパー）のために受け取る。
     */
    CafeAward rewardTask(CafeLearningProgress learning, String taskKey) {
        cafePassiveCashSinceTask = 0;
        // 枠が満タンに戻るので、復習で戻しておいたぶんは使わずに捨てる
        cafeReviewPassiveCredits = 0;
        resetCafePassiveClock();
        // テストケース数ではなく店舗の集客力で販売数を増やす。
        // 店舗ごとに同じ注文が入り、章クリアで育つブランド倍率を最後に掛ける。
        long cups = cupsPerNetworkOrderWithUpgrades();
        long cash = cafeCashForCups(cups, learning);
        return addCafeReward("task", cash, cups, taskKey);
    }

    /**
     * 期限が来た問題を復習で通したときの売上。
     *
     * <p>額は1問クリアの {@link #REVIEW_REWARD_PERCENT}% を基準に、復習手当系統で伸びる。
     * <b>「期限が来ていたか」を見るのはここではない</b> ―
     * {@code ProgressStore.recordMasterySubmission} が、期限の記録を書き換える前に判定して
     * 呼び分ける。期限の計算とその判定を同じ場所に置いておくためである。</p>
     *
     * <p>杯（cups）は増やさない。杯は初回の注文に紐づけたままにしてある
     * （クイズのチップと同じ扱い）。自動売上の枠は
     * {@link #noteReviewSubmission} が既に1問分戻しているので、ここでは触らない。</p>
     *
     * @param cleanRecall その日に一度も失敗せず通したか（思い出しのマドレーヌが見る）
     */
    CafeAward rewardReview(CafeLearningProgress learning, String taskKey, boolean cleanRecall) {
        rollReviewPayDay();
        // 期限ぶんは「通すと期限が動く」ので1日1回だが、控えておかないと同じ問題へ
        // 続けて「早めの復習」ぶんが払えてしまう
        cafeReviewPaidTasks.add(taskKey);
        return payReview(learning, REVIEW_REWARD_PERCENT, cleanRecall);
    }

    /**
     * 期限が来ていない問題（「早めの復習」）を通したときの売上。
     *
     * <p>額は {@link #REVIEW_EARLY_REWARD_PERCENT}% で、上限は<b>2つ</b>ある ―
     * <b>同じ問題からは1日1回まで</b>（{@link #cafeReviewPaidTasks}）と、
     * <b>1日に払うのは {@link #REVIEW_EARLY_REWARD_TASKS_PER_DAY} 問まで</b>。
     * どちらかに当たると {@link CafeAward#NONE} を返す。期限のような自然な上限が
     * 無い側なので、ここが唯一の歯止めである ―― <b>2つとも外さないこと。</b>
     * 集合だけだと「クリア済みを1日に何周もして稼ぐ」、本数だけだと
     * 「一番やさしい1問を連打して稼ぐ」がそれぞれ成立する。</p>
     *
     * <p>払うのは呼び出し側が「復習モードからの提出で、クリア済みの問題に正解し、
     * 期限は来ていなかった」と判定した回だけ（{@code ProgressStore.ReviewOutcome}）。</p>
     *
     * @param cleanRecall その日に一度も失敗せず通したか（思い出しのマドレーヌが見る）
     */
    CafeAward rewardEarlyReview(
            CafeLearningProgress learning, String taskKey, boolean cleanRecall) {
        rollReviewPayDay();
        if (cafeReviewPaidTasks.contains(taskKey)
                || cafeReviewEarlyPaid >= REVIEW_EARLY_REWARD_TASKS_PER_DAY) {
            return CafeAward.NONE;
        }
        cafeReviewEarlyPaid++;
        cafeReviewPaidTasks.add(taskKey);
        // addCafeReward が保存を予約するので、増やした本数もそこで一緒に書き出される
        return payReview(learning, REVIEW_EARLY_REWARD_PERCENT, cleanRecall);
    }

    /** 日が変わっていたら、その日に払った問題と本数を空に戻す。区切りは午前4時。 */
    private void rollReviewPayDay() {
        String today = LearningDay.todayText();
        if (!today.equals(cafeReviewPaidDay)) {
            cafeReviewPaidDay = today;
            cafeReviewPaidTasks.clear();
            cafeReviewEarlyPaid = 0;
        }
    }

    /** 復習の売上（期限ぶん・早めぶん共通）。割合だけが違う。 */
    private CafeAward payReview(
            CafeLearningProgress learning, int percent, boolean cleanRecall) {
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        long base = applyPercent(taskCash, percent);
        long cash = applyPercent(base, 100L + cafeReviewBonusPercent());
        // 素の金額どうしで比べる上限。設備を最大にしても当たらないが、値をいじったときの歯止め
        long cap = applyPercent(taskCash, REVIEW_REWARD_MAX_PERCENT);
        return addCafeReward("review", Math.min(cash, cap), 0, null, cleanRecall);
    }

    /** その日「早めの復習」へまだ払える本数。画面が残りを出すために読む。 */
    private int reviewEarlyRewardLeft() {
        if (!LearningDay.todayText().equals(cafeReviewPaidDay)) {
            return REVIEW_EARLY_REWARD_TASKS_PER_DAY;
        }
        return Math.max(0, REVIEW_EARLY_REWARD_TASKS_PER_DAY - cafeReviewEarlyPaid);
    }

    /** 章を初めて制覇したときのまとまったボーナス。 */
    CafeAward rewardChapter(
            String chapterId, CafeLearningProgress learning, int chapterTaskCount) {
        if (!rewardedChapters.add(chapterId)) {
            return CafeAward.NONE;
        }
        // 章内の通常報酬合計の25%を基準にする。2問章と12問章が同額になる偏りを避ける。
        long chapterOrderCups = saturatedMultiply(
                cupsPerNetworkOrderWithUpgrades(), Math.max(1, chapterTaskCount));
        long baseCups = ceilDivide(chapterOrderCups, 4L);
        long cups = applyPercent(baseCups, 100L + cafeChapterBonusPercent());
        long cash = cafeCashForCups(cups, learning);
        return addCafeReward("chapter", cash, cups);
    }

    /**
     * 1度目の回答で正解したクイズへチップを付ける。
     *
     * <p>呼ぶのは {@link #noteQuizAnswered} だけ（そこが「1度目か」を見ている）。
     * 集合の歯止めは、同じクイズへ二重に払わないための保険として残してある。</p>
     */
    private CafeAward rewardQuiz(String key, CafeLearningProgress learning) {
        if (!rewardedQuizzes.add(key)) {
            return CafeAward.NONE;
        }
        // 達成条件は呼び出し元（連続記録を進めた直後）で見直しているので、ここでは見ない
        // 現在の1問売上の2%を基準にする。難しい後半でもクイズの価値が薄れず、
        // クイズ接客設備を最大にしても通常の学習報酬を恒常的には超えない。
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        long baseTip = Math.max(saturatedMultiply(100L, cafeStores), applyPercent(taskCash, 2L));
        long cash = applyPercent(baseTip, 100L + cafeQuizTipPercent());
        return addCafeReward("quiz", cash, 0);
    }

    /** 設備を購入する。残高・直前ランク・重複購入をここで一括判定する。 */
    PurchaseResult purchaseCafeUpgrade(String id) {
        CafeUpgrade upgrade = CafeCatalog.UPGRADES.stream()
                .filter(u -> u.id().equals(id))
                .findFirst()
                .orElse(null);
        if (upgrade == null) {
            return new PurchaseResult(false, "その設備はありません", null, null);
        }
        if (cafeUpgrades.contains(id)) {
            return new PurchaseResult(false, "その設備は購入済みです", upgrade, null);
        }
        CafeUpgrade equipped = currentCafeUpgrade(upgrade.effectType());
        if (equipped != null && upgrade.tier() < equipped.tier()) {
            return new PurchaseResult(false,
                    "すでに上位設備「" + equipped.name() + "」を装備しています", upgrade, equipped);
        }
        int nextTier = equipped == null ? 1 : equipped.tier() + 1;
        if (upgrade.tier() != nextTier) {
            CafeUpgrade next = CafeCatalog.upgradeAt(upgrade.effectType(), nextTier);
            String nextName = next == null ? "現在の設備" : "「" + next.name() + "」";
            return new PurchaseResult(false,
                    "先に" + nextName + "へアップグレードしてください", upgrade, equipped);
        }
        long cost = cafeUpgradeCost(upgrade);
        if (cafeCash < cost) {
            return new PurchaseResult(false, "コインが足りません", upgrade, equipped);
        }
        cafeCash -= cost;
        cafeUpgrades.add(id);
        resetCafePassiveClock();
        saver.soon();
        return new PurchaseResult(true, null, upgrade, equipped);
    }

    /** 自動営業設備を、必要な★数と直前ランクを満たしたときだけ購入する。 */
    AutomationPurchaseResult purchaseCafeAutomation(String id) {
        CafeAutomation automation = CafeCatalog.AUTOMATION.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
        if (automation == null) {
            return new AutomationPurchaseResult(false, "その自動営業設備はありません", null, null);
        }
        if (cafeAutomationUpgrades.contains(id)) {
            return new AutomationPurchaseResult(false, "その自動営業設備は購入済みです",
                    automation, null);
        }
        CafeAutomation equipped = currentCafeAutomation();
        int nextTier = equipped == null ? 1 : equipped.tier() + 1;
        if (automation.tier() != nextTier) {
            CafeAutomation next = CafeCatalog.automationAt(nextTier);
            String nextName = next == null ? "現在の設備" : "「" + next.name() + "」";
            return new AutomationPurchaseResult(false,
                    "先に" + nextName + "へアップグレードしてください", automation, equipped);
        }
        long cost = cafeAutomationCost(automation);
        if (cafeCash < cost) {
            return new AutomationPurchaseResult(false, "コインが足りません", automation, equipped);
        }
        cafeCash -= cost;
        cafeAutomationUpgrades.add(id);
        resetCafePassiveClock();
        saver.soon();
        return new AutomationPurchaseResult(true, null, automation, equipped);
    }

    /** アプリ画面を表示した。ここを起点にするため、画面外だった時間は売上にならない。 */
    PassiveSalesResult startCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        cafePassiveSessionId = sessionId;
        cafePassiveLastTickNanos = System.nanoTime();
        cafePassiveRemainder = 0;
        return new PassiveSalesResult(0, cafePassiveCashPerMinute(learning), true);
    }

    /** 表示中のアプリ画面からの定期連絡ぶんだけ、自動売上を加算する。 */
    PassiveSalesResult collectCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        if (sessionId == null || !sessionId.equals(cafePassiveSessionId)) {
            return new PassiveSalesResult(0, cafePassiveCashPerMinute(learning), false);
        }
        long now = System.nanoTime();
        long elapsedMillis = Math.max(0L, (now - cafePassiveLastTickNanos) / 1_000_000L);
        elapsedMillis = Math.min(elapsedMillis, MAX_PASSIVE_TICK_MILLIS);
        cafePassiveLastTickNanos = now;

        long ratePerMinute = cafePassiveCashPerMinute(learning);
        // 復習で戻した枠は、1問ぶんの売上額が分かるここで初めて使う
        consumeReviewPassiveCredits(learning);
        long remaining = Math.max(0L, cafePassiveCashCap(learning) - cafePassiveCashSinceTask);
        if (ratePerMinute <= 0 || elapsedMillis <= 0 || remaining <= 0) {
            return new PassiveSalesResult(0, ratePerMinute, true);
        }
        long numerator = saturatedAdd(cafePassiveRemainder,
                saturatedMultiply(ratePerMinute, elapsedMillis));
        long earned = Math.min(remaining, numerator / 60_000L);
        cafePassiveRemainder = numerator % 60_000L;
        if (earned > 0) {
            cafeCash = saturatedAdd(cafeCash, earned);
            cafeLifetimeCash = saturatedAdd(cafeLifetimeCash, earned);
            cafePassiveCashSinceTask = saturatedAdd(cafePassiveCashSinceTask, earned);
            // ここは2.5秒ごとに来る。毎回書くとファイル全体の書き直しが延々と続くので、
            // 定期便に任せる（★や購入と違い、失っても次のtickで取り戻せる額）
            saver.eventually();
        }
        return new PassiveSalesResult(earned, ratePerMinute, true);
    }

    /** 画面を離れる直前までを精算して、自動営業セッションを閉じる。 */
    PassiveSalesResult stopCafePassiveSales(
            String sessionId, CafeLearningProgress learning) {
        PassiveSalesResult result = collectCafePassiveSales(sessionId, learning);
        if (sessionId != null && sessionId.equals(cafePassiveSessionId)) {
            cafePassiveSessionId = null;
            cafePassiveLastTickNanos = 0;
            cafePassiveRemainder = 0;
        }
        return new PassiveSalesResult(result.cash(), result.cashPerMinute(), false);
    }

    /**
     * 復習で戻した枠を、実際に「受け取った自動売上」から引く。
     *
     * <p>復習した時点では1問ぶんの売上額（完成した章の問題数が要る）が分からないので、
     * 問題数だけ数えておいて、自動売上を集めるここで換算する。0を下回らせないので、
     * 何問復習しても枠が既定の5問分より広くなることはない。</p>
     */
    private void consumeReviewPassiveCredits(CafeLearningProgress learning) {
        if (cafeReviewPassiveCredits <= 0) {
            return;
        }
        long oneTask = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        long refund = saturatedMultiply(oneTask, cafeReviewPassiveCredits);
        cafeReviewPassiveCredits = 0;
        cafePassiveCashSinceTask = Math.max(0L, cafePassiveCashSinceTask - refund);
        saver.eventually();
    }

    /** 発見済みのスペシャルアイテムを購入する。アイテムは設備とは別枠で全て同時に所持できる。 */
    ItemPurchaseResult purchaseCafeItem(String id) {
        CafeItem item = CafeCatalog.ITEMS.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElse(null);
        if (item == null) {
            return new ItemPurchaseResult(false, "そのアイテムはありません", null);
        }
        if (cafeItems.contains(id)) {
            return new ItemPurchaseResult(false, "そのアイテムは所持済みです", item);
        }
        if (!isCafeItemDiscovered(item)) {
            return new ItemPurchaseResult(false, "このアイテムはまだ発見されていません", item);
        }
        if (cafeCash < item.cost()) {
            return new ItemPurchaseResult(false, "コインが足りません", item);
        }
        cafeCash -= item.cost();
        cafeItems.add(id);
        resetCafePassiveClock();
        saver.soon();
        return new ItemPurchaseResult(true, null, item);
    }

    /** 現在までに解放されたアイテムを確認済みにする。未解放アイテムの存在は記録しない。 */
    void markCafeItemsSeen() {
        boolean changed = false;
        for (CafeItem item : CafeCatalog.ITEMS) {
            if ((cafeItems.contains(item.id()) || isCafeItemDiscovered(item))
                    && cafeSeenItems.add(item.id())) {
                changed = true;
            }
        }
        if (changed) {
            saver.soon();
        }
    }

    /** 現在の約50%にあたる新店舗をまとめて開く。出店するほど一度の拡大量も増える。 */
    ExpansionResult expandCafeNetwork() {
        if (cafeStores >= MAX_CAFE_STORES) {
            return new ExpansionResult(false, "店舗ネットワークは最大規模です",
                    cafeStores, 0, cafeStores, 0);
        }
        int storeLimit = currentCafeStoreLimit();
        if (cafeStores >= storeLimit) {
            Integer unlockStars = nextCafeStoreUnlockStars();
            String message = unlockStars == null
                    ? "店舗ネットワークは現在の上限です"
                    : "次の出店枠には★" + unlockStars + "が必要です";
            return new ExpansionResult(false, message, cafeStores, 0, cafeStores, 0);
        }
        long cost = nextCafeExpansionCost();
        if (cafeCash < cost) {
            return new ExpansionResult(false, "出店に必要なコインが足りません",
                    cafeStores, 0, cafeStores, cost);
        }
        int previousStores = cafeStores;
        int addedStores = nextCafeStoreGain(storeLimit);
        cafeCash -= cost;
        cafeStores += addedStores;
        refreshCafeAchievements();
        resetCafePassiveClock();
        saver.soon();
        return new ExpansionResult(true, null, previousStores, addedStores, cafeStores, cost);
    }

    /**
     * 終盤の任意プロジェクトを1段階完了する。
     *
     * <p>報酬倍率は増やさない。学習コンテンツが増えたときに、
     * 20問ごとに新しい使い道を自動で用意するための長期的なコイン消費先。</p>
     */
    InvestmentPurchaseResult purchaseCafeInvestment() {
        CafeInvestment investment = nextCafeInvestment();
        if (investment == null) {
            return new InvestmentPurchaseResult(false, "改装プロジェクトは上限です", null);
        }
        if (learningRecord.clearedTaskCount() < investment.requiredStars()) {
            return new InvestmentPurchaseResult(false,
                    "次の改装プロジェクトには★" + investment.requiredStars() + "が必要です",
                    investment);
        }
        if (cafeCash < investment.cost()) {
            return new InvestmentPurchaseResult(false,
                    "改装プロジェクトに必要なコインが足りません", investment);
        }
        cafeCash -= investment.cost();
        cafeInvestmentLevel = investment.level();
        resetCafePassiveClock();
        saver.soon();
        return new InvestmentPurchaseResult(true, null, investment);
    }

    private CafeAward addCafeReward(String trigger, long cash, long cups) {
        return addCafeReward(trigger, cash, cups, null);
    }

    private CafeAward addCafeReward(String trigger, long cash, long cups, String taskKey) {
        return addCafeReward(trigger, cash, cups, taskKey, false);
    }

    /**
     * @param cleanRecall 復習で、その日に一度も失敗せず通したか。
     *                    trigger が {@code "review"} のときだけ見る（思い出しのマドレーヌ）
     */
    private CafeAward addCafeReward(String trigger, long cash, long cups, String taskKey,
                                    boolean cleanRecall) {
        cafeRewardSequence = saturatedAdd(cafeRewardSequence, 1L);
        if (trigger.equals("task")) {
            cafeTaskRewardCount = saturatedAdd(cafeTaskRewardCount, 1L);
        }
        long rewardedCash = cash;
        List<String> itemEvents = new ArrayList<>();

        CafeItem luckyCoin = ownedItemWithEffect("lucky_double");
        if (luckyCoin != null
                && isLuckyHit(cafeRewardSequence, luckyCoin.effectValue("lucky_chance"))) {
            int times = luckyCoin.effectValue("lucky_double");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(luckyCoin.emoji() + " " + luckyCoin.name()
                    + "大当たり！ 獲得コイン+" + ((times - 1) * 100) + "%");
        }

        CafeItem comboBook = ownedItemWithEffect("task_combo");
        if (trigger.equals("task") && comboBook != null
                && cafeTaskRewardCount % TASK_COMBO_INTERVAL == 0) {
            int times = comboBook.effectValue("task_combo");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(comboBook.emoji() + " " + comboBook.name()
                    + "完成！ " + TASK_COMBO_INTERVAL + "問目ボーナス×" + times);
        }

        // 思い出しのマドレーヌは、復習手当系統の伸びとは別に最後へ乗る。倍率なので
        // 素の金額どうしの上限（REVIEW_REWARD_MAX_PERCENT）の外側 ―
        // ラッキーコインや生涯学習トロフィーと同じ扱いで、初回クリアにも乗るものと比べる。
        CafeItem madeleine = ownedItemWithEffect("review_recall_double");
        if (trigger.equals("review") && cleanRecall && madeleine != null) {
            int times = madeleine.effectValue("review_recall_double");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(madeleine.emoji() + " " + madeleine.name()
                    + "で一発で思い出せた×" + times);
        }

        Cleared clearedTask = taskKey == null ? null : learningRecord.cleared(taskKey);

        CafeItem tamper = ownedItemWithEffect("first_try_percent");
        if (trigger.equals("task") && tamper != null && clearedTask != null
                && clearedTask.hintsUsed() == 0 && clearedTask.attempts() <= 1) {
            int percent = tamper.effectValue("first_try_percent");
            rewardedCash = applyPercent(rewardedCash, 100L + percent);
            itemEvents.add(tamper.emoji() + " " + tamper.name() + "で一発クリア+" + percent + "%");
        }

        CafeItem dripper = ownedItemWithEffect("retry_double");
        if (trigger.equals("task") && dripper != null && clearedTask != null
                && clearedTask.attempts() >= RETRY_BONUS_ATTEMPTS) {
            int times = dripper.effectValue("retry_double");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(dripper.emoji() + " " + dripper.name() + "で粘りボーナス×" + times);
        }

        CafeItem quizMegaphone = ownedItemWithEffect("quiz_multiplier");
        if (trigger.equals("quiz") && quizMegaphone != null) {
            int times = quizMegaphone.effectValue("quiz_multiplier");
            rewardedCash = saturatedMultiply(rewardedCash, times);
            itemEvents.add(quizMegaphone.emoji() + " " + quizMegaphone.name()
                    + "で正解チップ×" + times);
        }

        CafeItem chapterCake = ownedItemWithEffect("chapter_percent");
        if (trigger.equals("chapter") && chapterCake != null) {
            int percent = chapterCake.effectValue("chapter_percent");
            rewardedCash = applyPercent(rewardedCash, percent);
            itemEvents.add(chapterCake.emoji() + " " + chapterCake.name()
                    + "で章制覇ボーナス×" + (percent / 100.0));
        }

        CafeItem lifelongTrophy = ownedItemWithEffect("mastery_bonus");
        if (lifelongTrophy != null) {
            int percent = lifelongTrophy.effectValue("mastery_bonus");
            rewardedCash = applyPercent(rewardedCash, 100L + percent);
            itemEvents.add(lifelongTrophy.emoji() + " " + lifelongTrophy.name()
                    + "で学習報酬+" + percent + "%");
        }
        cafeCash = saturatedAdd(cafeCash, rewardedCash);
        cafeLifetimeCash = saturatedAdd(cafeLifetimeCash, rewardedCash);
        cafeCups = saturatedAdd(cafeCups, cups);
        saver.soon();
        return new CafeAward(rewardedCash, cups, List.copyOf(itemEvents));
    }

    private boolean isCafeItemDiscovered(CafeItem item) {
        if (item.byAchievement()) {
            return cafeAchievements.contains(item.unlockAchievement());
        }
        return learningRecord.clearedTaskCount() >= item.unlockStars()
                && cafeLifetimeCash >= item.unlockLifetimeCash();
    }

    /**
     * 全問を終えてからカフェを開いた人にも、節目型アイテムの未所持分を贈る。
     *
     * <p>問題・章の売上は初回だけなので、設備を買わずに完走すると、その後は生涯売上を
     * 大きく増やせず後半アイテムが事実上取得不能になる。完走時だけ、現在の★条件を
     * 満たす達成型以外のアイテムを記念品として所持済みにする。通常どおり育てた人は
     * すでに所持しているため変化せず、何度呼んでも重複しない。</p>
     *
     * @return 今回贈ったアイテム数。追加がなければ0
     */
    int ensureCafeCompletionCatchUp(
            int currentCurriculumClearedTasks, int totalTaskCount) {
        if (totalTaskCount <= 0 || currentCurriculumClearedTasks < totalTaskCount) {
            return 0;
        }
        int added = 0;
        for (CafeItem item : CafeCatalog.ITEMS) {
            if (!item.byAchievement()
                    && currentCurriculumClearedTasks >= item.unlockStars()
                    && cafeItems.add(item.id())) {
                added++;
            }
        }
        if (added > 0) {
            saver.soon();
        }
        return added;
    }
    /**
     * 達成条件を見直して、満たしたものを記録する。
     *
     * いちど達成したら外さない。連続記録のように後で崩れるものもあるため、
     * 「今の状態」ではなく「達成したことがあるか」を残す。
     */
    boolean refreshCafeAchievements() {
        int busiestDay = learningRecord.busiestDayClears();
        if (LearningDay.todayText().equals(cafeMasteryDay)) {
            // 今日は復習で正解した分も「その日に触った問題」に数える。初クリアと同じ問題を
            // 二重に数えないよう、集合に入れてから大きさを見る
            Set<String> todayTasks = new LinkedHashSet<>(cafeMasteryDayTasks);
            todayTasks.addAll(learningRecord.clearedKeysOn(cafeMasteryDay));
            busiestDay = Math.max(busiestDay, todayTasks.size());
        }
        boolean changed = award("same_day_15", busiestDay >= 15);
        changed |= award("streak_7", learningRecord.longestClearStreak() >= 7);
        // レッスン画面での答え直しは数えない（表示された正解を押すだけで必要な問数がそろう）。
        // 数えるのは初回答の連続と、復習として出し直したクイズの連続の2つ
        // ―― 初回答は在庫が減る一方なので、片方だけだと取り逃しが起きる。
        changed |= award("quiz_streak_20",
                cafeQuizFirstStreak >= QUIZ_STREAK_ITEM_RUN
                        || cafeQuizMasteryRun.size() >= QUIZ_STREAK_ITEM_RUN);
        changed |= award("store_5", cafeStores >= 5);
        changed |= award("persistent_clear",
                learningRecord.maxAttemptsOnAnyTask() >= RETRY_ACHIEVEMENT_ATTEMPTS);
        // 重い2つ。復習ノートは全574問の3分の1以上、トロフィーは25問連続の無傷クリア
        changed |= award("review_200", cafeMasteryTasks.size() >= REVIEW_MASTERY_ITEM_TASKS);
        changed |= award("flawless_25",
                learningRecord.bestFlawlessRun() >= FLAWLESS_ITEM_RUN
                        || cafeMasteryTaskRun.size() >= FLAWLESS_ITEM_RUN);
        return changed;
    }

    /**
     * 章を全問クリアしたときだけ分かる達成条件を記録する。
     *
     * 章に属する問題キーは教材側しか知らないので、呼び出し側から渡してもらう。
     * 何度呼ばれても記録が増えるだけなので、報酬のような重複防止は要らない。
     */
    void noteChapterAchievements(List<String> chapterTaskKeys) {
        if (chapterTaskKeys.isEmpty()) {
            return;
        }
        boolean hintFree = true;
        boolean sameDay = true;
        String firstDay = null;
        for (String key : chapterTaskKeys) {
            Cleared c = learningRecord.cleared(key);
            if (c == null) {
                return;
            }
            if (c.hintsUsed() > 0) {
                hintFree = false;
            }
            if (firstDay == null) {
                firstDay = c.clearedAt();
            } else if (!firstDay.equals(c.clearedAt())) {
                sameDay = false;
            }
        }
        boolean masteredInReview = cafeMasteryTasks.containsAll(chapterTaskKeys);
        boolean masteredToday = LearningDay.todayText().equals(cafeMasteryDay)
                && cafeMasteryDayTasks.containsAll(chapterTaskKeys);
        boolean changed = award("chapter_no_hint", hintFree || masteredInReview);
        changed |= award("chapter_one_day", sameDay || masteredToday);
        if (changed) {
            saver.soon();
        }
    }

    private boolean award(String achievement, boolean reached) {
        return reached && cafeAchievements.add(achievement);
    }

    /**
     * 問題へ正解した1回ぶん、ラッキーコインの解放を抽選する。
     *
     * <p>種と抽選回数を保存するため、外れた直後に再起動しても同じ回を引き直せない。
     * いちど解放された後は抽選もカウントも止める。</p>
     *
     * @return 抽選回数または解放状態が変わったら true
     */
    private boolean drawLuckyCoinUnlock() {
        if (cafeAchievements.contains("lucky_coin_draw")) {
            return false;
        }
        cafeLuckyCoinUnlockDrawCount = saturatedAdd(cafeLuckyCoinUnlockDrawCount, 1L);
        if (isLuckyUnlockHit(cafeLuckyCoinUnlockSeed, cafeLuckyCoinUnlockDrawCount)) {
            cafeAchievements.add("lucky_coin_draw");
        }
        return true;
    }

    /** 保存された報酬回数から疑似乱数を作るため、再起動しても同じ報酬を引き直せない。 */
    private static boolean isLuckyHit(long sequence, int chancePercent) {
        long value = sequence ^ ((long) "lucky_coin".hashCode() << 32);
        return isLuckyValue(value, chancePercent);
    }

    /** 利用者ごとの種を混ぜた、ラッキーコイン解放専用の0.3%抽選。 */
    private static boolean isLuckyUnlockHit(long seed, long sequence) {
        long value = seed ^ ((long) "lucky_coin_unlock".hashCode() << 32)
                ^ Long.rotateLeft(sequence * 0x9e3779b97f4a7c15L, 17);
        return isLuckyValue(value, LUCKY_COIN_UNLOCK_CHANCE_PER_MILLE, 1_000L);
    }

    /** 抽選ごとに作った値を十分に混ぜ、100個の確率枠へ割り当てる（1%刻み）。 */
    private static boolean isLuckyValue(long value, int chancePercent) {
        return isLuckyValue(value, chancePercent, 100L);
    }

    /**
     * 混ぜた値を {@code buckets} 個の枠へ割り当て、先頭の {@code chance} 枠を当たりにする。
     *
     * <p>刻みは枠数で決まる ― 100なら1%刻み、1000なら0.1%刻み。枠数を変えると
     * <b>同じ種・同じ回数でも当たり外れが変わる</b>ので、当たる回を決め打ちしている検査
     * （{@code tools/AchievementCheck.java}・{@code tools/CafeBalanceSimulation.java} の
     * 試算用シード）は数え直すこと。</p>
     */
    private static boolean isLuckyValue(long value, int chance, long buckets) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return Long.remainderUnsigned(value, buckets) < chance;
    }
    /**
     * その効果を持つアイテムを所持していれば返す。していなければ null。
     *
     * 1つのアイテムが複数の効果を持つので、効果名から引いてから
     * {@link CafeItem#effectValue(String)} でその効果の値を取る。
     */
    private CafeItem ownedItemWithEffect(String effectType) {
        for (CafeItem item : CafeCatalog.ITEMS) {
            if (item.hasEffect(effectType) && cafeItems.contains(item.id())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 全報酬（問題・章・クイズ・自動売上）に掛かる唯一の売上%。
     *
     * <p>ここに足せる系統は販売戦略だけにする。かつて常連サービス系統を同じ和へ
     * 入れていたときは、値段が同じで効果だけが弱い「劣化した販売戦略」になっていた。
     * 系統を増やすときは、必ず別のスコープを持たせる ―
     * 復習手当は {@link #cafeReviewBonusPercent()}（期限が来た問題の復習だけ）である。</p>
     */
    private int cafeSalesBonusPercent() {
        return cafeEffectTotal("sales");
    }

    /**
     * 復習手当系統の効果。期限が来た問題を復習で通したときの報酬にだけ掛かる。
     *
     * 初回クリアへも乗せると販売戦略系統と同じ変数になり、同じ買い物が2系統に分かれる。
     */
    private int cafeReviewBonusPercent() {
        return cafeEffectTotal("review");
    }

    private int cafeExtraCups() {
        return cafeEffectTotal("cups");
    }

    private int cafeChapterBonusPercent() {
        return cafeEffectTotal("chapter");
    }

    private int cafeQuizTipPercent() {
        return cafeEffectTotal("tips");
    }

    private int cafeEffectTotal(String effectType) {
        CafeUpgrade equipped = currentCafeUpgrade(effectType);
        return equipped == null ? 0 : equipped.effectValue();
    }

    private CafeUpgrade currentCafeUpgrade(String effectType) {
        CafeUpgrade current = null;
        for (CafeUpgrade upgrade : CafeCatalog.UPGRADES) {
            if (cafeUpgrades.contains(upgrade.id())
                    && upgrade.effectType().equals(effectType)
                    && (current == null || upgrade.tier() > current.tier())) {
                current = upgrade;
            }
        }
        return current;
    }

    private long cupsPerOrderWithUpgrades() {
        return CafeCatalog.levelFor(learningRecord.clearedTaskCount()).cupsPerOrder() + cafeExtraCups();
    }

    private long cupsPerNetworkOrderWithUpgrades() {
        CafeItem truck = ownedItemWithEffect("store_bonus");
        long stores = truck == null ? cafeStores : cafeStores + truck.effectValue("store_bonus");
        return saturatedMultiply(cupsPerOrderWithUpgrades(), stores);
    }

    private long cafeCashForCups(long cups, CafeLearningProgress learning) {
        long baseCash = saturatedMultiply(cups, CUP_PRICE);
        long cashWithEquipment = applyPercent(baseCash, 100L + cafeSalesBonusPercent());
        return applyBasisPoints(cashWithEquipment, cafeBrandMultiplierBasisPoints(learning));
    }

    /**
     * ブランド倍率。初回クリアと復習の2つが育て、報酬すべてに掛かる。
     *
     * <p>初回は完成した章に含まれる問題数で加算し、短い章だけを先取りする攻略を防ぐ。
     * 復習ぶんは {@link #cafeReviewBrandBasisPoints()} が持つ。</p>
     */
    private long cafeBrandMultiplierBasisPoints(CafeLearningProgress learning) {
        long growth = saturatedMultiply(Math.max(0, learning.masteredChapterTasks()),
                BRAND_GROWTH_BASIS_POINTS_PER_TASK);
        long basisPoints = saturatedAdd(10_000L, growth);
        return saturatedAdd(basisPoints, cafeReviewBrandBasisPoints());
    }

    /**
     * 復習が育てたブランド倍率ぶん。
     *
     * <p>復習にコインは払わず、ここで倍率だけを育てる。1問につき1回しか数えないので
     * （集合で持っている）、解き直しを繰り返しても増えない。倍率は<b>これから</b>の
     * 報酬へ掛かるため、早く復習した人ほど得になる ―「間隔をあけて復習してほしい」
     * という教材側の狙いと、報酬の形が一致する。</p>
     *
     * <p>1問あたりの上限は復習ノートの4倍が乗って160で、初回クリアの170を超えない。
     * この順序は {@code tools/simulate-cafe.sh} が検査する。</p>
     */
    private long cafeReviewBrandBasisPoints() {
        long growth = saturatedMultiply(cafeMasteryTasks.size(),
                REVIEW_BRAND_GROWTH_BASIS_POINTS_PER_TASK);
        CafeItem note = ownedItemWithEffect("review_brand_multiplier");
        return note == null
                ? growth
                : saturatedMultiply(growth, note.effectValue("review_brand_multiplier"));
    }

    private int currentCafeStoreLimit() {
        int limit = 1;
        int stars = learningRecord.clearedTaskCount();
        for (int i = 0; i < STORE_UNLOCK_STARS.length; i++) {
            if (stars >= STORE_UNLOCK_STARS[i]) {
                limit = STORE_LIMITS[i];
            }
        }
        return limit;
    }

    /** 現在の★数で購入できる任意投資の最高段階。 */
    private int currentCafeInvestmentAvailableLevel() {
        int starsPastStart = learningRecord.clearedTaskCount() - ENDGAME_INVESTMENT_START_STARS;
        return starsPastStart <= 0 ? 0 : starsPastStart / ENDGAME_INVESTMENT_STAR_INTERVAL;
    }

    private boolean cafeInvestmentVisible() {
        return cafeInvestmentLevel > 0
                || learningRecord.clearedTaskCount() >= ENDGAME_INVESTMENT_START_STARS
                        + ENDGAME_INVESTMENT_STAR_INTERVAL;
    }

    private CafeInvestment nextCafeInvestment() {
        int level = cafeInvestmentLevel + 1;
        int requiredStars = ENDGAME_INVESTMENT_START_STARS
                + level * ENDGAME_INVESTMENT_STAR_INTERVAL;
        return new CafeInvestment(
                level,
                cafeInvestmentName(level),
                cafeInvestmentEmoji(level),
                cafeInvestmentDescription(level),
                requiredStars,
                cafeInvestmentCost(level));
    }

    private static long cafeInvestmentCost(int level) {
        long cost = ENDGAME_INVESTMENT_BASE_COST;
        for (int i = 1; i < level; i++) {
            cost = saturatedMultiply(cost, ENDGAME_INVESTMENT_STEP_NUMERATOR)
                    / ENDGAME_INVESTMENT_STEP_DENOMINATOR;
        }
        return cost;
    }

    private static String cafeInvestmentName(int level) {
        return switch (level) {
            case 1 -> "フレームワーク認定ラウンジ";
            case 2 -> "運用管制センター";
            case 3 -> "Java Café記念館";
            default -> "Javaコミュニティ基金 第" + (level - 3) + "期";
        };
    }

    private static String cafeInvestmentEmoji(int level) {
        return switch (level) {
            case 1 -> "🏛️";
            case 2 -> "🛰️";
            case 3 -> "🏛️";
            default -> "🌱";
        };
    }

    private static String cafeInvestmentDescription(int level) {
        return switch (level) {
            case 1 -> "3製品の学びを称える認定ラウンジを開設します。";
            case 2 -> "世界の店舗を見守る運用・可観測性の拠点を作ります。";
            case 3 -> "積み重ねたJava学習を後世へ残す記念館を開設します。";
            default -> "次の学習者を支えるコミュニティ活動へ投資します。";
        };
    }

    private Map<String, Object> cafeInvestmentToClientJson(CafeInvestment investment) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("level", investment.level());
        value.put("name", investment.name());
        value.put("emoji", investment.emoji());
        value.put("description", investment.description());
        value.put("requiredStars", investment.requiredStars());
        value.put("cost", investment.cost());
        value.put("available", learningRecord.clearedTaskCount() >= investment.requiredStars());
        value.put("completedLevel", cafeInvestmentLevel);
        value.put("availableLevel", currentCafeInvestmentAvailableLevel());
        value.put("rewardEffect", false);
        return value;
    }

    private Integer nextCafeStoreUnlockStars() {
        for (int i = 0; i < STORE_LIMITS.length; i++) {
            if (STORE_LIMITS[i] > cafeStores && learningRecord.clearedTaskCount() < STORE_UNLOCK_STARS[i]) {
                return STORE_UNLOCK_STARS[i];
            }
        }
        return null;
    }

    private int nextCafeStoreGain(int storeLimit) {
        int remaining = Math.min(MAX_CAFE_STORES, storeLimit) - cafeStores;
        int growth = Math.max(1, (cafeStores + 1) / 2);
        return Math.min(remaining, growth);
    }

    /**
     * 序盤は規模の二乗、5店舗以降は三乗で上がる出店費。
     * 後半の大きな収入にも見合う、長期のコイン消費先にする。
     */
    private long nextCafeExpansionCost() {
        long square = saturatedMultiply(cafeStores, cafeStores);
        long quadraticCost = saturatedMultiply(FIRST_EXPANSION_COST, square);
        long cubicCost = saturatedMultiply(EXPANSION_CUBIC_COST, saturatedMultiply(square, cafeStores));
        long baseCost = Math.max(quadraticCost, cubicCost);
        CafeItem toolbox = ownedItemWithEffect("expansion_discount");
        return toolbox == null
                ? baseCost
                : discountedCost(baseCost, toolbox.effectValue("expansion_discount"));
    }

    private long cafeUpgradeCost(CafeUpgrade upgrade) {
        return discountedCost(upgrade.cost(), equipmentDiscountPercent());
    }

    private long cafeAutomationCost(CafeAutomation automation) {
        return discountedCost(automation.cost(), equipmentDiscountPercent());
    }

    /** 設備費の割引率。マイスター工具箱を持っていれば20%。 */
    private int equipmentDiscountPercent() {
        CafeItem toolbox = ownedItemWithEffect("equipment_discount");
        return toolbox == null ? 0 : toolbox.effectValue("equipment_discount");
    }

    /**
     * クリア済みの問題のうち、復習で仕上げた割合（0〜100）。画面表示に使う。
     *
     * 分母から概念レッスンの★を外す。概念レッスンは提出課題を持たないので解き直せず、
     * 数に入れると100%へ到達できない（復習を全部終えた人にも未完に見える）。
     */
    private int reviewedTaskPercent() {
        int reviewable = 0;
        for (String key : learningRecord.clearedKeys()) {
            if (!Lesson.isConceptKey(key)) {
                reviewable++;
            }
        }
        if (reviewable == 0) {
            return 0;
        }
        int reviewed = 0;
        for (String key : cafeMasteryTasks) {
            if (learningRecord.cleared(key) != null) {
                reviewed++;
            }
        }
        return Math.min(100, reviewed * 100 / reviewable);
    }

    private CafeAutomation currentCafeAutomation() {
        CafeAutomation current = null;
        for (CafeAutomation automation : CafeCatalog.AUTOMATION) {
            if (cafeAutomationUpgrades.contains(automation.id())
                    && (current == null || automation.tier() > current.tier())) {
                current = automation;
            }
        }
        return current;
    }

    /** 自動売上は現在の1問クリア売上の最大5%/分。 */
    private long cafePassiveCashPerMinute(CafeLearningProgress learning) {
        CafeAutomation automation = currentCafeAutomation();
        if (automation == null) {
            return 0;
        }
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        return Math.max(1L, applyBasisPoints(taskCash, automation.rateBasisPointsPerMinute()));
    }

    private long cafePassiveCashCap(CafeLearningProgress learning) {
        long taskCash = cafeCashForCups(cupsPerNetworkOrderWithUpgrades(), learning);
        return applyBasisPoints(taskCash, PASSIVE_CASH_CAP_BASIS_POINTS);
    }

    /** 投資直前の経過時間へ、購入後の高いレートをさかのぼって適用しない。 */
    private void resetCafePassiveClock() {
        if (cafePassiveSessionId != null) {
            cafePassiveLastTickNanos = System.nanoTime();
            cafePassiveRemainder = 0;
        }
    }

    private static long discountedCost(long cost, int discountPercent) {
        return Math.max(1L, applyPercent(cost, 100L - discountPercent));
    }

    private static long applyPercent(long value, long percent) {
        return saturatedMultiply(value, percent) / 100L;
    }

    private static long applyBasisPoints(long value, long basisPoints) {
        return saturatedMultiply(value, basisPoints) / 10_000L;
    }

    private static long ceilDivide(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return saturatedAdd(value, divisor - 1L) / divisor;
    }

    private static long saturatedMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
