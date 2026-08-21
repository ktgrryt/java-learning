import jq.content.Chapter;
import jq.content.ContentLoader;
import jq.content.Curriculum;
import jq.content.Lesson;
import jq.content.Task;
import jq.progress.LearningDay;
import jq.progress.ProgressStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全教材を順番に初回クリアし、買える経営要素を安い順に購入する経済回帰テスト。
 * 本番と同じ ProgressStore の計算を使うため、設備追加後の終盤詰まりやコイン余りを確認できる。
 *
 * <p>完走したときの投資率だけでなく、<b>序盤の解放ペース</b>も見る
 * （{@link #verifyEarlyPacing}）。設備の歯止めは価格だけなので、Rank1が1問の報酬に対して
 * 安いと最初の章で店が一気に完成してしまう。</p>
 *
 * <p>2つのシナリオを回す。効果が「学習の仕方」に依存する要素（期限が来た問題の復習報酬、
 * 復習が育てるブランド倍率）は、片方だけでは測れないため。</p>
 *
 * <ul>
 *   <li><b>plain</b> … 同じ日に一気に完走し、復習は一度もしない。復習ぶんの倍率も
 *       復習報酬も0になる。<b>収入の下限</b>で、投資率25〜45%を守る基準。</li>
 *   <li><b>reviewer</b> … 7日連続で通い、クリアした問題をその場で復習し、確認クイズも
 *       解き直し、<b>期限ぶんの報酬を1問1回ずつ受け取る</b>（{@link #reviewTask}）。
 *       復習アイテムが全て効く<b>収入の上限</b>。ここでは投資率の下限を緩め、
 *       「全て買えること」と「復習が初回クリアを追い越さないこと」を見る。</li>
 * </ul>
 */
public final class CafeBalanceSimulation {

    /** 117回目でラッキーコインを引く、再現可能な通常試算用シード。 */
    private static final long STANDARD_LUCKY_UNLOCK_SEED = 77_777L;
    /** 584回の初回正解では引かない（初当たりは812回目）、最も不運な場合の境界試算用シード。 */
    private static final long UNLUCKY_UNLOCK_SEED = 47L;

    private static final Set<Integer> MILESTONES = Set.of(
            1, 20, 50, 100, 170, 240, 310, 370, 420, 460, 480, 493, 500, 503, 507,
            520, 540, 560, 574, 578, 580, 581, 583, 584);

    /** 初回クリア1問あたりのブランド成長。復習ぶんがこれを超えないことを確かめる。 */
    private static final long FIRST_CLEAR_BRAND_BASIS_POINTS_PER_TASK = 170;

    /**
     * 復習1問の報酬が、初回クリア1問に対して取っていい割合の上限（%）。
     *
     * <p>{@code CafeEconomy.REVIEW_REWARD_MAX_PERCENT} と同じ値。100%へ届くと
     * 「新しい問題を解くより復習した方が儲かる」状態になる。設備を買い切った状態が
     * 94%（50% × 1.88）なので、ここは96%を歯止めとして置く。</p>
     */
    private static final long REVIEW_REWARD_CEILING_PERCENT = 96;

    /** 序盤ペースの検査に使う★の範囲。最初の2章が収まる長さにする。 */
    private static final int EARLY_TRACE_STARS = 60;

    private record Candidate(String kind, String id, long cost) {
    }

    /**
     * 買う順番の方針。
     *
     * <p>★の解放条件を外したので、歯止めは価格だけになった。ある方針だけが極端に
     * 得（または損）だと「選択の自由」が見かけ倒しになるため、方針を変えて回して
     * 差を測る。CHEAPEST が基準、他は極端な寄せ方。</p>
     */
    private enum Strategy {
        /** 買えるものを安い順に。上のRankほど1コインあたりの価値が下がるので、これが素直な打ち手 */
        CHEAPEST,
        /** 買えるもののうち最も高いものへ。1系統を深く掘る「Rank先取り」型 */
        PRICIEST,
        /** 抽出力（序盤ほど倍率が大きい系統）を優先し、余ったら安い順 */
        RUSH_CUPS,
        /** 販売戦略（終盤ほど倍率が大きい系統）を優先し、余ったら安い順 */
        RUSH_SALES
    }

    /**
     * 1シナリオの結果。
     *
     * @param earlyFacilities ★1個目から順に、その時点で持っている設備＋自動営業の数。
     *                        序盤の解放ペースを見るため {@link #EARLY_TRACE_STARS} 個まで
     */
    private record Outcome(
            String label,
            Map<String, Object> cafe,
            double spendPercent,
            int clearedTasks,
            int investments,
            boolean streakSeeded,
            List<Integer> earlyFacilities) {

        long lifetime() {
            return number(cafe.get("lifetimeCash"));
        }

        /**
         * この試算で到達できるアイテム数（全12種）。
         *
         * 復習しないシナリオでは、重い2つのうち「復習で異なる200問」（復習ノート）と、
         * 「7日連続で学習」（思い出しのマドレーヌ）へ届かないので10種。
         * もう1つの重い条件「無傷で25問連続」（生涯学習トロフィー）は、この試算が
         * ヒントをほぼ使わないぶん、どちらのシナリオでも満たす。
         */
        int expectedItems() {
            return streakSeeded ? 12 : 10;
        }
    }

    private CafeBalanceSimulation() {
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Curriculum curriculum = new ContentLoader(project.resolve("content")).load();

        Outcome plain = simulate(
                curriculum, "plain", false, 0, true, STANDARD_LUCKY_UNLOCK_SEED);
        verifyContentReachable(curriculum, plain);
        verifyLearningStaysAhead(plain);
        verifyEarlyPacing(curriculum, plain);
        require(plain.spendPercent() >= 25.0 && plain.spendPercent() <= 45.0,
                "復習なしの投資率が目標25〜45%を外れています: " + plain.spendPercent() + "%");
        require(number(plain.cafe().get("reviewBrandBasisPoints")) == 0,
                "復習していないのにブランド倍率へ復習ぶんが乗っています");
        require(number(plain.cafe().get("equipmentDiscountPercent")) == 20,
                "マイスター工具箱の設備費20%OFFが効いていません");

        // 0.3%抽選なので、全584問を解いても外れ続ける人が約17%いる（1%のころは約0.3%）。
        // 珍しい筋書きではないので、未解放でも必須設備・店舗・終盤投資を買えて、
        // 投資率が破綻しないことをここで見る。
        Outcome unlucky = simulate(
                curriculum, "unlucky", false, 0, false, UNLUCKY_UNLOCK_SEED);
        verifyUnluckyContentReachable(curriculum, unlucky);
        require(unlucky.spendPercent() >= 25.0 && unlucky.spendPercent() <= 45.0,
                "ラッキーコイン未解放時の投資率が目標25〜45%を外れています: "
                        + unlucky.spendPercent() + "%");

        Outcome reviewer = simulate(
                curriculum, "reviewer", true, 7, false, STANDARD_LUCKY_UNLOCK_SEED);
        verifyContentReachable(curriculum, reviewer);
        verifyLearningStaysAhead(reviewer);
        require(reviewer.spendPercent() <= 45.0,
                "復習ありの投資率が上限45%を超えています: " + reviewer.spendPercent() + "%");
        // 収入が増えるぶん投資率は下がる。ここは「コインが余りすぎていない」ことだけ見る
        require(reviewer.spendPercent() >= 10.0,
                "復習ありでコインが余りすぎています（投資率 " + reviewer.spendPercent() + "%）");
        require(reviewer.lifetime() > plain.lifetime(),
                "復習しても生涯売上が増えていません");
        verifyReviewStaysBehindFirstClear(reviewer);
        require(number(reviewer.cafe().get("reviewBonusPercent"))
                        == number(plain.cafe().get("reviewBonusPercent")),
                "復習手当系統は買い方で決まるので、両シナリオで同じRankまで買えるはず");

        // ★の解放条件を外したので、歯止めは価格だけになった。ある買い方だけが極端に
        // 得だと「どの系統から伸ばしてもいい」が見かけ倒しになるため、方針を変えて比べる。
        verifyNoDominantStrategy(curriculum, plain);

        System.out.printf("%nplain    spend=%.2f%% lifetime=%,d brand=x%.2f (review +%.2f)%n",
                plain.spendPercent(), plain.lifetime(),
                number(plain.cafe().get("brandMultiplierBasisPoints")) / 10_000.0,
                number(plain.cafe().get("reviewBrandBasisPoints")) / 10_000.0);
        System.out.printf("reviewer spend=%.2f%% lifetime=%,d brand=x%.2f (review +%.2f)%n",
                reviewer.spendPercent(), reviewer.lifetime(),
                number(reviewer.cafe().get("brandMultiplierBasisPoints")) / 10_000.0,
                number(reviewer.cafe().get("reviewBrandBasisPoints")) / 10_000.0);
        System.out.printf("序盤ペース 設備数: ★2=%d ★6=%d 1章=%d 2章=%d%n",
                facilitiesAt(plain.earlyFacilities(), 2),
                facilitiesAt(plain.earlyFacilities(), 6),
                facilitiesAt(plain.earlyFacilities(),
                        curriculum.taskCount(curriculum.chapters().get(0))),
                facilitiesAt(plain.earlyFacilities(),
                        curriculum.taskCount(curriculum.chapters().get(0))
                                + curriculum.taskCount(curriculum.chapters().get(1))));
        System.out.println("BALANCE OK: 復習なし／復習あり／ラッキーコイン未解放で"
                + "購入到達性・投資率・学習優位の上限・序盤の解放ペースを確認しました");
    }

    /**
     * 1シナリオを最後まで走らせる。
     *
     * @param review    クリアした問題をその場で復習し、確認クイズも解き直すか
     * @param streakDays 進捗ファイルへ先に書いておく連続学習日数
     *                   （7日あると「7日連続で学習」の達成型アイテムが解放される）
     * @param printRows 途中の節目を表として出すか
     */
    private static Outcome simulate(
            Curriculum curriculum, String label, boolean review, int streakDays, boolean printRows)
            throws Exception {
        return simulate(curriculum, label, review, streakDays, printRows,
                Strategy.CHEAPEST, STANDARD_LUCKY_UNLOCK_SEED);
    }

    private static Outcome simulate(
            Curriculum curriculum, String label, boolean review, int streakDays,
            boolean printRows, long luckyUnlockSeed) throws Exception {
        return simulate(curriculum, label, review, streakDays, printRows,
                Strategy.CHEAPEST, luckyUnlockSeed);
    }

    private static Outcome simulate(
            Curriculum curriculum, String label, boolean review, int streakDays,
            boolean printRows, Strategy strategy)
            throws Exception {
        return simulate(curriculum, label, review, streakDays, printRows,
                strategy, STANDARD_LUCKY_UNLOCK_SEED);
    }

    private static Outcome simulate(
            Curriculum curriculum, String label, boolean review, int streakDays,
            boolean printRows, Strategy strategy, long luckyUnlockSeed)
            throws Exception {
        Path tempDir = Files.createTempDirectory("java-cafe-balance-");
        Path progressFile = tempDir.resolve("progress.json");
        try {
            seedProgress(progressFile, streakDays, luckyUnlockSeed);
            ProgressStore progress = new ProgressStore(progressFile);
            boolean firstTask = true;
            List<Integer> earlyFacilities = new ArrayList<>();

            if (printRows) {
                System.out.println("star\tchapters\tcash\tlifetime\tnextTask\tstores"
                        + "\tupgrades\tauto\titems\tinvestment");
            }
            for (Chapter chapter : curriculum.chapters()) {
                for (Lesson lesson : chapter.lessons()) {
                    for (Task task : lesson.tasks()) {
                        // 任意発展問題は章クリア・★・カフェ経済の分母に含めない。
                        if (!task.required()) continue;
                        String key = Lesson.taskKey(lesson.id(), task.id());
                        // 粘りのドリッパーは「1問へ累計10回提出」で解放される。最初の1問だけ
                        // 粘った形にしておく（無傷の連続はここで1回切れるだけで、
                        // 584問あれば25問連続はその後いくらでも成立する）
                        if (firstTask) {
                            for (int i = 0; i < 10; i++) {
                                progress.recordAttempt(key);
                            }
                            firstTask = false;
                        }
                        // 本番と同じく、正解の記録を初クリア判定より先に通す。
                        // ラッキーコインの0.3%解放抽選はこの共通経路で行われる。
                        progress.recordMasterySubmission(key, true);
                        progress.markCleared(key);
                        ProgressStore.CafeLearningProgress learning = learning(curriculum, progress);
                        progress.rewardTask(learning, key);
                        if (review) {
                            reviewTask(progress, learning, key);
                        }
                        if (curriculum.isChapterCleared(chapter, progress.clearedIds())) {
                            progress.noteChapterAchievements(chapterTaskKeys(chapter));
                            progress.rewardChapter(
                                    chapter.id(), learning, curriculum.taskCount(chapter));
                        }
                        buyAllAffordable(progress, learning(curriculum, progress), strategy);
                        if (earlyFacilities.size() < EARLY_TRACE_STARS) {
                            earlyFacilities.add(facilityCount(
                                    cafe(progress, learning(curriculum, progress))));
                        }
                        if (printRows && MILESTONES.contains(progress.clearedIds().size())) {
                            printRow(progress, learning(curriculum, progress));
                        }
                    }
                    for (int i = 0; i < lesson.quizzes().size(); i++) {
                        int answer = lesson.quizzes().get(i).answer();
                        // 1度目の回答で正解する筋書き（チップが出るのはこのときだけ）
                        progress.recordQuiz(
                                lesson.id(), i, answer, true, learning(curriculum, progress));
                        if (review) {
                            // 押し直しても何も入らないことを、試算の側でも通しておく
                            progress.recordQuiz(
                                    lesson.id(), i, answer, true, learning(curriculum, progress));
                        }
                        buyAllAffordable(progress, learning(curriculum, progress), strategy);
                    }

                    // 概念レッスンは提出課題を持たず、クイズ全問正解で★が1つ付く。★はカフェの
                    // 経済の分母（totalTaskCount）に入るので、本番と同じ順番でここでも通す。
                    // 復習は対象外（解き直す提出物が無い）ので、苦手度と復習の記録は動かさない。
                    if (lesson.concept()) {
                        String key = lesson.conceptKey();
                        progress.markCleared(key);
                        ProgressStore.CafeLearningProgress learning = learning(curriculum, progress);
                        progress.rewardTask(learning, key);
                        if (curriculum.isChapterCleared(chapter, progress.clearedIds())) {
                            progress.noteChapterAchievements(chapterTaskKeys(chapter));
                            progress.rewardChapter(
                                    chapter.id(), learning, curriculum.taskCount(chapter));
                        }
                        buyAllAffordable(progress, learning(curriculum, progress), strategy);
                        if (earlyFacilities.size() < EARLY_TRACE_STARS) {
                            earlyFacilities.add(facilityCount(
                                    cafe(progress, learning(curriculum, progress))));
                        }
                        if (printRows && MILESTONES.contains(progress.clearedIds().size())) {
                            printRow(progress, learning(curriculum, progress));
                        }
                    }
                }
            }

            Map<String, Object> cafe = cafe(progress, learning(curriculum, progress));
            long lifetime = number(cafe.get("lifetimeCash"));
            long cash = number(cafe.get("cash"));
            double spendPercent = lifetime == 0 ? 0 : (lifetime - cash) * 100.0 / lifetime;
            int expectedInvestments =
                    Math.max(0, (curriculum.totalTaskCount() - 500) / 20);
            System.out.printf("FINAL[%s] spend=%,d (%.2f%%) cash=%,d lifetime=%,d%n",
                    label, lifetime - cash, spendPercent, cash, lifetime);
            return new Outcome(label, cafe, spendPercent,
                    progress.clearedIds().size(), expectedInvestments, streakDays >= 7,
                    List.copyOf(earlyFacilities));
        } finally {
            Files.deleteIfExists(progressFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * 1問を「何度も間違えてから復習で仕上げ、のちに期限ぶんを1回受け取った」形にする。
     *
     * <p>間違えてから通すのは、苦手度が復習モードの出題順に効くため。</p>
     *
     * <p><b>期限ぶんの報酬は、金額の計算だけを直接呼んでいる。</b>クリアした直後は
     * 期限が翌日以降にあるので {@code duePassed} にはならず（すぐ下で確かめている）、
     * 日付を戻さないと1コインも入らない。ただし日付を戻して完走後にまとめて払うと、
     * 末期の単価で584問ぶんが一度に入って上限側を測り損なう（実測 lifetime が
     * 119兆→297兆、投資率5.66%まで落ちた）。<b>ここで払えば、初回クリアと同じ時点の
     * 単価で受け取る</b> ―― 実際の利用者も、翌日の復習は翌日の店構えで受け取る。</p>
     *
     * <p>受け取るのは<b>1問につき1回だけ</b>で、「完走した時点で全問を1周ぶん復習し終えた
     * 状態」を表す。<b>「早めの復習」ぶん（期限前の小額）は入らない</b> ―― 同じ問題から
     * 1日に入るのは1回だけなので、期限ぶんを受け取ったこの問題では0になる。実際の利用者は
     * 期限切れが足りない日に別の問題で受け取る（1日6問まで）ため、投資率の下限側
     * （コインが余りすぎていないか）は試算より低く出る。間隔が伸びるあいだに同じ問題から2周目・3周目も入るが、それは
     * 自動売上と同じで<b>時間に対して無制限</b>なので、完走時点のスナップショットには
     * 入れない。期限そのものの上限（期限前は0・同じ日の2回目は0）は
     * {@code tools/check-review-economy.sh} が試している。</p>
     */
    private static void reviewTask(ProgressStore progress,
                                  ProgressStore.CafeLearningProgress learning, String key) {
        for (int i = 0; i < 3; i++) {
            progress.recordMasterySubmission(key, false);
        }
        ProgressStore.ReviewOutcome outcome = progress.recordMasterySubmission(key, true, true);
        require(!outcome.duePassed(),
                "クリアした当日の復習で期限ぶんが払われています: " + key);
        require(outcome.earlyPassed(),
                "クリアした当日の復習が「早めの復習」に数えられていません: " + key);
        // 一発で思い出せた側（cleanRecall=true）で受け取る。上限側の試算なので、
        // 思い出しのマドレーヌの×2もここで効かせる
        progress.rewardReview(learning, key, true);
        // 「早めの復習」ぶん（期限前の小額）は**この試算に入らない**。同じ問題から1日に
        // 入るのは1回だけなので、いま期限ぶんを受け取ったこの問題は0を返す ―
        // 実際の利用者は「期限切れが足りない日に、別の問題で」受け取る（1日6問まで）。
        // つまり試算は復習の収入を**下振れで**見ており、上限側（投資率45%）は安全側、
        // 下限側（コインが余りすぎていないか）は試算より低く出る。だから復習手当系統の
        // 価格を上げて吸い込みを作ってある。上限そのものは check-review-economy.sh が試す
        require(progress.rewardEarlyReview(learning, key, true).cash() == 0,
                "同じ問題へ期限ぶんと早めぶんの両方が払われています: " + key);
    }

    /** 抽選を再現可能にし、必要なら直近の連続学習日数も置いた進捗ファイルを作る。 */
    private static void seedProgress(Path progressFile, int days, long luckyUnlockSeed)
            throws Exception {
        StringBuilder dates = new StringBuilder();
        LocalDate first = LearningDay.today().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            dates.append(i == 0 ? "\"" : ",\"").append(first.plusDays(i)).append("\"");
        }
        Files.writeString(progressFile,
                "{\"clearDates\":[" + dates + "],\"cafe\":{" +
                        "\"economyVersion\":21,\"luckyCoinUnlockSeed\":"
                        + luckyUnlockSeed + "}}",
                StandardCharsets.UTF_8);
    }

    /** 章に属する全問題のキー。達成条件の判定へ渡す。 */
    private static List<String> chapterTaskKeys(Chapter chapter) {
        List<String> keys = new ArrayList<>();
        for (Lesson lesson : chapter.lessons()) {
            keys.addAll(lesson.taskKeys());
        }
        return keys;
    }

    private static ProgressStore.CafeLearningProgress learning(
            Curriculum curriculum, ProgressStore progress) {
        Set<String> cleared = progress.clearedIds();
        int chapters = 0;
        int masteredTasks = 0;
        for (Chapter chapter : curriculum.chapters()) {
            if (curriculum.isChapterCleared(chapter, cleared)) {
                chapters++;
                masteredTasks += curriculum.taskCount(chapter);
            }
        }
        return new ProgressStore.CafeLearningProgress(chapters, masteredTasks);
    }

    private static void buyAllAffordable(
            ProgressStore progress,
            ProgressStore.CafeLearningProgress learning,
            Strategy strategy) {
        for (int guard = 0; guard < 200; guard++) {
            Map<String, Object> cafe = cafe(progress, learning);
            long cash = number(cafe.get("cash"));
            List<Candidate> candidates = new ArrayList<>();
            collectPurchases(candidates, list(cafe.get("upgrades")), "upgrade", cash);
            collectPurchases(candidates, list(cafe.get("automation")), "automation", cash);
            for (Map<String, Object> item : list(cafe.get("items"))) {
                if (!bool(item.get("owned")) && number(item.get("cost")) <= cash) {
                    candidates.add(new Candidate("item", String.valueOf(item.get("id")),
                            number(item.get("cost"))));
                }
            }
            if (cafe.get("expansionCost") instanceof Number n && n.longValue() <= cash) {
                candidates.add(new Candidate("expansion", "", n.longValue()));
            }
            Map<String, Object> investment = map(cafe.get("endgameInvestment"));
            if (bool(investment.get("available")) && number(investment.get("cost")) <= cash) {
                candidates.add(new Candidate("investment", "", number(investment.get("cost"))));
            }
            Candidate next = pick(candidates, strategy);
            if (next == null) {
                return;
            }
            switch (next.kind()) {
                case "upgrade" -> progress.purchaseCafeUpgrade(next.id());
                case "automation" -> progress.purchaseCafeAutomation(next.id());
                case "item" -> progress.purchaseCafeItem(next.id());
                case "expansion" -> progress.expandCafeNetwork();
                case "investment" -> progress.purchaseCafeInvestment();
                default -> throw new IllegalStateException(next.kind());
            }
        }
        throw new IllegalStateException("購入ループが収束しません");
    }

    /** 方針に従って、買えるものの中から1つ選ぶ。 */
    private static Candidate pick(List<Candidate> candidates, Strategy strategy) {
        if (candidates.isEmpty()) {
            return null;
        }
        String preferred = switch (strategy) {
            case RUSH_CUPS -> "cups";
            case RUSH_SALES -> "sales";
            default -> null;
        };
        if (preferred != null) {
            Candidate track = candidates.stream()
                    .filter(c -> c.kind().equals("upgrade") && TRACK_OF.get(c.id()) != null
                            && TRACK_OF.get(c.id()).equals(preferred))
                    .max(Comparator.comparingLong(Candidate::cost))
                    .orElse(null);
            if (track != null) {
                return track;
            }
        }
        Comparator<Candidate> byCost = Comparator.comparingLong(Candidate::cost);
        return strategy == Strategy.PRICIEST
                ? candidates.stream().max(byCost).orElse(null)
                : candidates.stream().min(byCost).orElse(null);
    }

    /** 設備ID -> 系統。RUSH_* の方針で「その系統だけ先に買う」ために使う。 */
    private static final Map<String, String> TRACK_OF = new java.util.HashMap<>();

    private static void collectPurchases(
            List<Candidate> result, List<Map<String, Object>> entries, String kind, long cash) {
        for (Map<String, Object> entry : entries) {
            long cost = number(entry.get("cost"));
            if (entry.get("effectType") instanceof String type) {
                TRACK_OF.put(String.valueOf(entry.get("id")), type);
            }
            if (bool(entry.get("available")) && !bool(entry.get("owned")) && cost <= cash) {
                result.add(new Candidate(kind, String.valueOf(entry.get("id")), cost));
            }
        }
    }

    private static void printRow(
            ProgressStore progress, ProgressStore.CafeLearningProgress learning) {
        Map<String, Object> cafe = cafe(progress, learning);
        System.out.printf("%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                progress.clearedIds().size(), learning.clearedChapters(),
                number(cafe.get("cash")), number(cafe.get("lifetimeCash")),
                number(cafe.get("nextOrderCash")), number(cafe.get("storeCount")),
                list(cafe.get("ownedUpgrades")).size(), list(cafe.get("ownedAutomation")).size(),
                list(cafe.get("ownedItems")).size(), number(cafe.get("investmentLevel")));
    }

    /** どちらのシナリオでも、買えるものは最後まで全部買えていること。 */
    private static void verifyContentReachable(Curriculum curriculum, Outcome outcome) {
        Map<String, Object> cafe = outcome.cafe();
        String at = "[" + outcome.label() + "] ";
        require(outcome.clearedTasks() == curriculum.totalTaskCount(),
                at + "全問題を完走できていません");
        require(number(cafe.get("level")) == 12, at + "最終カフェレベルへ到達できません");
        require(number(cafe.get("storeCount")) == number(cafe.get("maxStores")),
                at + "最大店舗数へ到達できません");
        require(list(cafe.get("ownedUpgrades")).size() == 60,
                at + "通常設備60個を全購入できません");
        require(list(cafe.get("ownedAutomation")).size() == 12,
                at + "自動営業設備12個を全購入できません");
        require(number(cafe.get("investmentLevel")) == outcome.investments(),
                at + "学習20問ごとの終盤任意投資を全て完了できません");
        require(list(cafe.get("ownedItems")).size() == outcome.expectedItems(),
                at + "この試算で達成できるスペシャルアイテム"
                        + outcome.expectedItems() + "個を全購入できません");
    }

    /** 抽選へ外れ続けても、ラッキーコイン以外の全経営要素へ到達できること。 */
    private static void verifyUnluckyContentReachable(Curriculum curriculum, Outcome outcome) {
        Map<String, Object> cafe = outcome.cafe();
        String at = "[" + outcome.label() + "] ";
        require(outcome.clearedTasks() == curriculum.totalTaskCount(), at + "全問題を完走できません");
        require(number(cafe.get("level")) == 12, at + "最終カフェレベルへ到達できません");
        require(number(cafe.get("storeCount")) == number(cafe.get("maxStores")),
                at + "最大店舗数へ到達できません");
        require(list(cafe.get("ownedUpgrades")).size() == 60,
                at + "通常設備60個を全購入できません");
        require(list(cafe.get("ownedAutomation")).size() == 12,
                at + "自動営業設備12個を全購入できません");
        require(number(cafe.get("investmentLevel")) == outcome.investments(),
                at + "終盤任意投資を全て完了できません");
        require(list(cafe.get("ownedItems")).size() == 9,
                at + "ラッキーコイン以外の到達可能な9アイテムを全購入できません");
    }

    /**
     * 序盤は「少しずつできることが増える」ペースであること。
     *
     * <p>設備の歯止めは価格だけなので、Rank1が安いと数問解いた時点で6系統すべての
     * Rank1が同時に買え、最初の章で店が一気に完成してしまう。1節はおおむね2問なので、
     * 「1節では1つも買えない」「3節以内には1つ買える」を両端に置き、そこから
     * 章ごとの上下限で緩やかさを縛る。</p>
     *
     * <p>上限だけでなく下限も見る。序盤を絞りすぎると、こんどは何章も進めても
     * 店が育たない ―「学習が売上になる」という手応えそのものが薄れる。</p>
     */
    private static void verifyEarlyPacing(Curriculum curriculum, Outcome outcome) {
        List<Integer> owned = outcome.earlyFacilities();
        String at = "[" + outcome.label() + "] ";
        require(owned.size() >= EARLY_TRACE_STARS,
                at + "序盤ペースを見るには★" + EARLY_TRACE_STARS + "分の教材が必要です");
        require(facilitiesAt(owned, 2) == 0,
                at + "★2（1節）で設備が買えてしまいます（"
                        + facilitiesAt(owned, 2) + "個）。Rank1が1問の報酬に対して安すぎます");
        require(facilitiesAt(owned, 6) >= 1,
                at + "★6（3節）でも設備が1つも買えません。Rank1が高すぎます");

        // 章の区切りは教材から数える。章の問題数が変わっても検査がずれない。
        int firstChapter = curriculum.taskCount(curriculum.chapters().get(0));
        int secondChapter = firstChapter + curriculum.taskCount(curriculum.chapters().get(1));
        require(secondChapter <= EARLY_TRACE_STARS,
                at + "最初の2章が★" + EARLY_TRACE_STARS + "に収まりません");
        int afterFirst = facilitiesAt(owned, firstChapter);
        int afterSecond = facilitiesAt(owned, secondChapter);
        require(afterFirst >= 2 && afterFirst <= 3,
                at + "1章クリア時点の設備が2〜3個から外れています: " + afterFirst + "個");
        require(afterSecond >= 5 && afterSecond <= 7,
                at + "2章クリア時点の設備が5〜7個から外れています: " + afterSecond + "個");
    }

    /** ★n個目を取り終えた時点で持っていた設備＋自動営業の数。 */
    private static int facilitiesAt(List<Integer> earlyFacilities, int star) {
        return earlyFacilities.get(star - 1);
    }

    private static int facilityCount(Map<String, Object> cafe) {
        return list(cafe.get("ownedUpgrades")).size() + list(cafe.get("ownedAutomation")).size();
    }

    /** 自動売上が学習を追い越さないこと。 */
    private static void verifyLearningStaysAhead(Outcome outcome) {
        Map<String, Object> cafe = outcome.cafe();
        String at = "[" + outcome.label() + "] ";
        long taskCash = number(cafe.get("nextOrderCash"));
        require(number(cafe.get("passiveCashPerMinute")) <= taskCash / 20L,
                at + "自動売上が学習1回分の5%/分を超えています");
        require(number(cafe.get("passiveCashCap")) <= taskCash * 5L,
                at + "次の★までの自動売上が学習5回分を超えています");
        // 全報酬へ掛かる倍率は販売戦略系統だけが持つ。ここが崩れると他の系統が
        // 「値段が同じで効果だけ弱い販売戦略」になる（2系統が同じ変数を取り合う）。
        require(number(cafe.get("bonusPercent")) == number(cafe.get("salesBonusPercent")),
                at + "販売戦略以外のボーナスが全報酬へ掛かっています");
    }

    /**
     * 復習1問が、初回クリア1問のブランド成長を超えないこと。
     *
     * ここが逆転すると「新しい問題を解くより、解いた問題を復習した方が儲かる」状態になる。
     */
    private static void verifyReviewStaysBehindFirstClear(Outcome outcome) {
        Map<String, Object> cafe = outcome.cafe();
        long reviewedTasks = number(cafe.get("reviewedTasks"));
        require(reviewedTasks > 0, "[reviewer] 復習が1問も数えられていません");
        long perTask = number(cafe.get("reviewBrandBasisPoints")) / reviewedTasks;
        require(perTask < FIRST_CLEAR_BRAND_BASIS_POINTS_PER_TASK,
                "[reviewer] 復習1問のブランド成長(" + perTask + ")が初回クリア("
                        + FIRST_CLEAR_BRAND_BASIS_POINTS_PER_TASK + ")以上です");
        // 金額の側も同じ順序でなければならない。買い切った状態の復習手当を含めて、
        // 復習1問が初回クリア1問（=100%）へ届かないこと
        long share = number(cafe.get("reviewRewardPercent"))
                * (100 + number(cafe.get("reviewBonusPercent"))) / 100;
        require(share > 0, "[reviewer] 復習報酬が0%です");
        require(share <= REVIEW_REWARD_CEILING_PERCENT,
                "[reviewer] 復習1問の報酬が初回クリアの" + share + "%まで来ています（上限"
                        + REVIEW_REWARD_CEILING_PERCENT + "%）");
    }

    /**
     * どの買い方でも極端な差がつかないこと。
     *
     * <p>設備の★解放条件を外したあと、選択の自由が本物であるためには
     * 「これを買うのが正解」という一手が無いことが要る。安い順・高い順・
     * 特定系統への集中で完走し、生涯売上が基準（安い順）から離れすぎないか見る。</p>
     *
     * <p>安い順が最良になるのは設計どおり ― 上のRankほど1コインあたりの価値が
     * 下がるため。ここで見たいのは、他の買い方が「選ぶ意味がないほど」損では
     * ないこと（下振れ）と、抜け道になっていないこと（上振れ）。</p>
     */
    private static void verifyNoDominantStrategy(Curriculum curriculum, Outcome baseline)
            throws Exception {
        System.out.println();
        for (Strategy strategy : new Strategy[] {
                Strategy.PRICIEST, Strategy.RUSH_CUPS, Strategy.RUSH_SALES }) {
            Outcome o = simulate(curriculum, "plain/" + strategy, false, 0, false, strategy);
            double ratio = o.lifetime() * 1.0 / baseline.lifetime();
            System.out.printf("  買い方 %-12s lifetime=%,d (安い順の %.2f倍) spend=%.2f%%%n",
                    strategy, o.lifetime(), ratio, o.spendPercent());
            verifyContentReachable(curriculum, o);
            verifyLearningStaysAhead(o);
            require(ratio <= 1.5,
                    "買い方 " + strategy + " が安い順の1.5倍を超えて得です（" + ratio + "倍）");
            require(ratio >= 0.2,
                    "買い方 " + strategy + " が安い順の2割を下回ります（" + ratio
                            + "倍）。選ぶ意味が無いほど損なので価格差を見直してください");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("BALANCE FAILED: " + message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cafe(
            ProgressStore progress, ProgressStore.CafeLearningProgress learning) {
        Map<String, Object> root = (Map<String, Object>) progress.toClientJson(learning);
        return (Map<String, Object>) root.get("cafe");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }
}
