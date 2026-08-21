package jq.progress;

import jq.progress.ProgressStore.CafeAutomation;
import jq.progress.ProgressStore.CafeItem;
import jq.progress.ProgressStore.CafeItemEffect;
import jq.progress.ProgressStore.CafeUpgrade;

import java.util.List;
import java.util.Map;

/**
 * カフェに「何が存在するか」の定義。
 *
 * <p>設備・スペシャルアイテム・自動営業設備・店のレベル・アイテムの解放条件文を、
 * データとして並べるだけの置き場である。<b>状態を持たず、規則も持たない。</b>
 * 「いくらで買えるか」「どう育つか」といった調整つまみは {@link CafeEconomy} にある
 * （効果の値だけはここに書いてある ― 設備そのものの説明文と一体だからである）。</p>
 *
 * <p>ここを読むだけで「いま何種類あるか」「どの系統がどこまで伸びるか」が分かるようにしてある。
 * 増やすときはこのファイルだけを触れば済み、逆に売上の計算式を直したいときは
 * このファイルを読む必要がない。</p>
 *
 * <p>記録として残す型（{@link CafeUpgrade} など）は {@link ProgressStore} の中に置いたままにしている。
 * 画面と検査ツールが {@code ProgressStore.CafeUpgrade} の名前で参照しているためで、
 * ここではその型を組み立てるだけである。</p>
 */
final class CafeCatalog {

    private CafeCatalog() {
    }

    private static CafeItemEffect fx(String type, int value) {
        return new CafeItemEffect(type, value);
    }

    record CafeLevel(int level, String title, int threshold, int cupsPerOrder) {
    }

    static final List<CafeLevel> LEVELS = List.of(
            new CafeLevel(1, "屋台カフェ", 0, 1),
            new CafeLevel(2, "街角のコーヒースタンド", 6, 2),
            new CafeLevel(3, "こだわりの小さな店", 20, 4),
            new CafeLevel(4, "人気カフェ", 50, 8),
            new CafeLevel(5, "大型ロースタリー", 100, 16),
            new CafeLevel(6, "Java Café チェーン", 170, 32),
            new CafeLevel(7, "世界的Javaカフェ", 240, 64),
            new CafeLevel(8, "テック街区ロースタリー", 310, 96),
            new CafeLevel(9, "全国Java Café連合", 370, 160),
            new CafeLevel(10, "アジア太平洋チェーン", 420, 256),
            new CafeLevel(11, "世界開発者ラウンジ", 474, 384),
            new CafeLevel(12, "Java Café 殿堂", 505, 512));

    static final List<CafeUpgrade> UPGRADES = List.of(
            new CafeUpgrade("welcome_mat", "ウェルカムマット", "🟫",
                    "来店率を上げる · 注文売上 +2%", 6_000, 1, "sales", 2),
            new CafeUpgrade("extra_mugs", "追加マグセット", "🥤",
                    "一度に出せる数を増やす · 毎注文 +1杯", 12_000, 1, "cups", 1),
            new CafeUpgrade("stamp_card", "スタンプカード", "🎫",
                    "常連客を増やす · 章ボーナス +10%", 4_000, 1, "chapter", 10),
            new CafeUpgrade("tip_jar", "小さなチップ瓶", "🫙",
                    "クイズを楽しむお客さんが増える · 正解チップ +25%", 1_800, 1, "tips", 25),
            new CafeUpgrade("morning_playlist", "朝のプレイリスト", "🎵",
                    "毎日通いたくなる空間 · 連続1日ごと 今日の1杯目 +3%", 3_000, 1, "streak", 3),

            new CafeUpgrade("signboard", "手書きの看板", "🪧",
                    "店を見つけてもらいやすくする · 注文売上 +6%", 25_000, 2, "sales", 6),
            new CafeUpgrade("hand_grinder", "手挽きミル", "🫘",
                    "抽出を並行できる · 毎注文 +2杯", 51_000, 2, "cups", 2),
            new CafeUpgrade("dripper", "ドリップスタンド", "🫗",
                    "繁忙時の抽出を安定させる · 章ボーナス +20%", 17_000, 2, "chapter", 20),
            new CafeUpgrade("cookie_plate", "試食クッキープレート", "🍪",
                    "正解を祝うひと口サービス · 正解チップ +50%", 7_600, 2, "tips", 50),
            new CafeUpgrade("window_seat", "窓際の指定席", "🪟",
                    "毎日の常連席をつくる · 連続1日ごと 今日の1杯目 +6%", 12_700, 2, "streak", 6),

            new CafeUpgrade("grinder", "セラミックグラインダー", "⚙️",
                    "豆の品質で客単価アップ · 注文売上 +13%", 106_000, 3, "sales", 13),
            new CafeUpgrade("brew_station", "第2抽出ステーション", "🫖",
                    "二つの注文を同時に作る · 毎注文 +4杯", 215_000, 3, "cups", 4),
            new CafeUpgrade("showcase", "焼き菓子ケース", "🧁",
                    "章末のまとめ買いを増やす · 章ボーナス +30%", 72_000, 3, "chapter", 30),
            new CafeUpgrade("latte_art", "ラテアート練習台", "🎨",
                    "正解祝いの一杯を特別に · 正解チップ +80%", 32_000, 3, "tips", 80),
            new CafeUpgrade("study_table", "学習者の大テーブル", "📚",
                    "学び続ける常連が集まる · 連続1日ごと 今日の1杯目 +9%", 53_000, 3, "streak", 9),

            new CafeUpgrade("espresso", "エスプレッソマシン", "☕",
                    "高単価メニューを提供 · 注文売上 +23%", 450_000, 4, "sales", 23),
            new CafeUpgrade("seats", "くつろぎテーブル", "🪑",
                    "同時に迎えられる客を増やす · 毎注文 +8杯", 900_000, 4, "cups", 8),
            new CafeUpgrade("weekend_event", "週末コーヒーイベント", "🎪",
                    "章末にお客さんを集める · 章ボーナス +45%", 305_000, 4, "chapter", 45),
            new CafeUpgrade("dessert_pairing", "デザートペアリング", "🍰",
                    "知識と味の組み合わせを祝う · 正解チップ +110%", 135_000, 4, "tips", 110),
            new CafeUpgrade("loyalty_board", "常連ネームボード", "🏷️",
                    "連続来店を店内で称える · 連続1日ごと 今日の1杯目 +12%", 225_000, 4, "streak", 12),

            new CafeUpgrade("roaster", "小型ロースター", "🔥",
                    "自家焙煎でブランド化 · 注文売上 +38%", 1_900_000, 5, "sales", 38),
            new CafeUpgrade("kitchen", "増設キッチン", "🍳",
                    "大量の注文へ対応 · 毎注文 +16杯", 3_800_000, 5, "cups", 16),
            new CafeUpgrade("terrace", "テラス貸切プラン", "⛱️",
                    "章末に団体客を呼ぶ · 章ボーナス +60%", 1_300_000, 5, "chapter", 60),
            new CafeUpgrade("tasting_flight", "飲み比べフライト", "🥃",
                    "正解後の体験価値を上げる · 正解チップ +145%", 570_000, 5, "tips", 145),
            new CafeUpgrade("daily_roast_log", "本日の焙煎ログ", "📋",
                    "学習と焙煎を毎日記録 · 連続1日ごと 今日の1杯目 +15%", 950_000, 5, "streak", 15),

            new CafeUpgrade("pos", "POSレジ", "🖥️",
                    "販売データで価格を最適化 · 注文売上 +58%", 8_000_000, 6, "sales", 58),
            new CafeUpgrade("mobile", "モバイルオーダー端末", "📱",
                    "店外からの注文も受ける · 毎注文 +32杯", 16_000_000, 6, "cups", 32),
            new CafeUpgrade("subscription", "豆の定期便", "📦",
                    "章末に定期購入が入る · 章ボーナス +80%", 5_600_000, 6, "chapter", 80),
            new CafeUpgrade("barista_school", "バリスタ講座", "🎓",
                    "正解の価値を伝える接客 · 正解チップ +180%", 2_400_000, 6, "tips", 180),
            new CafeUpgrade("commuter_pass", "常連パスポート", "🪪",
                    "日々の来店を習慣化 · 連続1日ごと 今日の1杯目 +18%", 4_000_000, 6, "streak", 18),

            new CafeUpgrade("second_store", "フランチャイズ本部", "🏢",
                    "全店の販売戦略を統一する · 注文売上 +88%", 40_000_000, 7, "sales", 88),
            new CafeUpgrade("delivery", "デリバリー車両", "🛵",
                    "広い地域の注文へ対応 · 毎注文 +64杯", 80_000_000, 7, "cups", 64),
            new CafeUpgrade("factory", "焙煎ファクトリー", "🏭",
                    "章末に全店へ豆を出荷 · 章ボーナス +105%", 28_000_000, 7, "chapter", 105),
            new CafeUpgrade("vip_counter", "VIPカウンター", "💎",
                    "クイズ好きの特別席 · 正解チップ +220%", 12_000_000, 7, "tips", 220),
            new CafeUpgrade("daily_newsletter", "毎朝のニュースレター", "📰",
                    "常連へ学びの話題を届ける · 連続1日ごと 今日の1杯目 +21%", 20_000_000, 7, "streak", 21),

            new CafeUpgrade("flagship_store", "フラッグシップ店", "🏛️",
                    "街の名所になり客単価上昇 · 注文売上 +128%", 260_000_000L, 8, "sales", 128),
            new CafeUpgrade("robot_barista", "ロボットバリスタ", "🤖",
                    "大量注文を正確に抽出 · 毎注文 +128杯", 520_000_000L, 8, "cups", 128),
            new CafeUpgrade("catering", "法人ケータリング", "🚚",
                    "章末に大型注文を獲得 · 章ボーナス +135%", 182_000_000L, 8, "chapter", 135),
            new CafeUpgrade("concierge", "コーヒーコンシェルジュ", "🤵",
                    "知識に合わせて一杯を提案 · 正解チップ +260%", 78_000_000L, 8, "tips", 260),
            new CafeUpgrade("habit_app", "学習習慣アプリ", "📲",
                    "毎日の来店を楽しく通知 · 連続1日ごと 今日の1杯目 +24%", 130_000_000L, 8, "streak", 24),

            new CafeUpgrade("airport_store", "空港ラウンジ店", "✈️",
                    "世界の旅行客へ販売 · 注文売上 +183%", 2_000_000_000L, 9, "sales", 183),
            new CafeUpgrade("smart_kitchen", "スマートキッチン", "🦾",
                    "全工程を自動連携 · 毎注文 +228杯", 4_000_000_000L, 9, "cups", 228),
            new CafeUpgrade("coffee_festival", "都市コーヒーフェス", "🎆",
                    "章末に街じゅうを集客 · 章ボーナス +170%", 1_400_000_000L, 9, "chapter", 170),
            new CafeUpgrade("members_lounge", "会員制ラウンジ", "🛋️",
                    "正解を語り合う上質な席 · 正解チップ +300%", 600_000_000L, 9, "tips", 300),
            new CafeUpgrade("mentor_club", "朝活メンタークラブ", "🌅",
                    "仲間と毎日学び続ける · 連続1日ごと 今日の1杯目 +27%", 1_000_000_000L, 9, "streak", 27),

            new CafeUpgrade("global_brand", "グローバルブランド", "🌍",
                    "世界共通の一杯へ · 注文売上 +258%", 15_000_000_000L, 10, "sales", 258),
            new CafeUpgrade("coffee_lab", "全自動コーヒーラボ", "🧪",
                    "研究設備で超大量抽出 · 毎注文 +388杯", 30_000_000_000L, 10, "cups", 388),
            new CafeUpgrade("world_expo", "ワールドコーヒーEXPO", "🎡",
                    "章末に世界規模の注文 · 章ボーナス +210%", 10_500_000_000L, 10, "chapter", 210),
            new CafeUpgrade("founders_club", "創業者クラブ", "👑",
                    "最高の学びへ最大級の祝福 · 正解チップ +350%", 4_500_000_000L, 10, "tips", 350),
            new CafeUpgrade("learning_retreat", "学習リトリート", "🏝️",
                    "学びを生活の一部にする · 連続1日ごと 今日の1杯目 +30%", 7_500_000_000L, 10, "streak", 30),

            new CafeUpgrade("quantum_campaign", "量子級ブランドキャンペーン", "🪐",
                    "開発者コミュニティ全体へ届ける · 注文売上 +360%", 110_000_000_000L, 11, "sales", 360),
            new CafeUpgrade("orbital_roastery", "軌道ロースタリー", "🛰️",
                    "軌道上の巨大設備で抽出 · 毎注文 +640杯", 220_000_000_000L, 11, "cups", 640),
            new CafeUpgrade("developer_summit", "世界開発者サミット", "🧑‍🚀",
                    "章末に世界の学習者が集う · 章ボーナス +260%", 77_000_000_000L, 11, "chapter", 260),
            new CafeUpgrade("knowledge_vault", "知識の宝物庫", "🏆",
                    "正解の知識を価値ある体験へ · 正解チップ +400%", 33_000_000_000L, 11, "tips", 400),
            new CafeUpgrade("learning_guild", "世界学習ギルド", "🤝",
                    "仲間と学ぶ文化を世界へ · 連続1日ごと 今日の1杯目 +33%", 55_000_000_000L, 11, "streak", 33),

            new CafeUpgrade("java_legacy", "Javaレガシー殿堂", "🏛️",
                    "積み重ねた学びを永続するブランドへ · 注文売上 +500%", 820_000_000_000L, 12, "sales", 500),
            new CafeUpgrade("planetary_brew", "惑星間ブリューシステム", "🚀",
                    "惑星規模の注文を同時抽出 · 毎注文 +1024杯", 1_640_000_000_000L, 12, "cups", 1_024),
            new CafeUpgrade("mastery_congress", "マスタリー世界会議", "🎓",
                    "全章の学びを祝う最大イベント · 章ボーナス +320%", 574_000_000_000L, 12, "chapter", 320),
            new CafeUpgrade("hall_of_fame_counter", "殿堂カウンター", "🥇",
                    "最高難度の正解を盛大に祝う · 正解チップ +450%", 246_000_000_000L, 12, "tips", 450),
            new CafeUpgrade("lifelong_academy", "生涯学習アカデミー", "♾️",
                    "学び続ける文化を完成させる · 連続1日ごと 今日の1杯目 +36%", 410_000_000_000L, 12, "streak", 36));

    /**
     * スペシャルアイテム12種。1種類ずつしか持てず、<b>1枚が持つ効果は1つだけ</b>。
     *
     * <p>効果を1枚へ束ねると、そのカードが何のカードなのか言えなくなる
     * （「一発で解いた」と「5回以上粘った」のように条件が正反対のものまで同居した）。
     * 12枠に収まるところまで効果を削り、残したものは全て単独カードにしてある。</p>
     *
     * <p>削った効果の値は、同じ軸に残したカードへ寄せている ―
     * 当日ボーナスはコンボスタンプ帳（5問ごと6倍→7倍）へ、
     * ブランド+0.25は生涯学習トロフィー（+10%→+13%）へ、
     * 復習ぶんの重み付け3つは復習ノート（倍率を4倍）へ。</p>
     *
     * <p>そのコンボスタンプ帳はのちに7倍→<b>5倍</b>へ下げ、下げたぶんを
     * ラッキーコインの大当たり（+100%→<b>+400%</b>）へ移している。ほぼ同じ価格
     * （80,000と77,777）なのに生涯コインへの効きが +27.5% と +3.7% で7倍以上離れ、
     * <b>一番取りにくい1枚が一番弱かった</b>ため。確率ではなく倍率を上げたのは、
     * 同じ期待倍率でも確率を上げるほうが投資率を大きく食うから
     * （当たる回は {@code cafeRewardSequence} だけで決まるので、確率を上げると
     * 章制覇やコンボが乗った大きい回を拾いやすい）。</p>
     *
     * <p>解放は2通り。{@code unlockAchievement} が空なら★数と累計コイン（学習の節目）、
     * 値があれば {@link #ACHIEVEMENT_NOTES} の達成条件で解放する。
     * 最後の2つ（復習ノート・生涯学習トロフィー）だけは条件が重い。</p>
     */
    static final List<CafeItem> ITEMS = List.of(
            new CafeItem("lucky_coin", "ラッキーコイン", "🪙",
                    "問題・章・クイズ報酬で5%の確率で大当たり（獲得コイン+400%）",
                    77_777L, 0, 0L, "lucky_coin_draw",
                    List.of(fx("lucky_double", 5), fx("lucky_chance", 5))),
            new CafeItem("golden_bean", "コンボスタンプ帳", "🗒️",
                    "問題を5問クリアするたび、その問題の獲得コインが必ず5倍",
                    80_000L, 0, 0L, "same_day_15",
                    List.of(fx("task_combo", 5))),
            new CafeItem("first_try_tamper", "一発仕上げのタンパー", "🥄",
                    "ヒントなし・1回の提出でクリアした問題の獲得コインが+20%",
                    15_000L, 0, 0L, "chapter_no_hint",
                    List.of(fx("first_try_percent", 20))),
            new CafeItem("persistence_dripper", "粘りのドリッパー", "💧",
                    "5回以上提出してクリアした問題の獲得コインが2倍",
                    12_000L, 0, 0L, "persistent_clear",
                    List.of(fx("retry_double", 2))),
            new CafeItem("attendance_calendar", "皆勤の日めくり", "📅",
                    "「今日の1杯目」に数える連続日数の上限が7日から10日に広がる",
                    120_000L, 0, 0L, "streak_7",
                    List.of(fx("streak_cap", 10))),
            new CafeItem("food_truck", "移動販売トラック", "🚚",
                    "注文の集客が2店舗ぶん増える（店舗が少ないうちほど効く）",
                    900_000L, 0, 0L, "store_5",
                    List.of(fx("store_bonus", 2))),
            new CafeItem("quiz_crown", "ひらめきメガホン", "📣",
                    "確認クイズに1度目の回答で正解したときのチップが必ず20倍",
                    250_000L, 0, 0L, "quiz_streak_20",
                    List.of(fx("quiz_multiplier", 20))),
            new CafeItem("fortune_cat", "祝福のホールケーキ", "🎂",
                    "章を初めて制覇したときの獲得コインが必ず4.5倍",
                    2_000_000L, 0, 0L, "chapter_one_day",
                    List.of(fx("chapter_percent", 450))),
            new CafeItem("fever_bell", "フランチャイズ地図", "🗺️",
                    "新店舗の出店費用がいつでも25%OFF",
                    20_000_000L, 170, 50_000_000L, "",
                    List.of(fx("expansion_discount", 25))),
            new CafeItem("java_relic", "マイスター工具箱", "🧰",
                    "すべての設備アップグレード費用がいつでも20%OFF",
                    200_000_000L, 240, 500_000_000L, "",
                    List.of(fx("equipment_discount", 20))),

            // ここから2つは取得条件が重い。全問の3分の1以上の復習、または25問連続の無傷クリア。
            new CafeItem("quiz_festival_pass", "復習ノート", "📖",
                    "復習で育つブランド倍率の成長が4倍になる",
                    110_000_000_000L, 0, 0L, "review_200",
                    List.of(fx("review_brand_multiplier", 4))),
            new CafeItem("lifelong_trophy", "生涯学習トロフィー", "🏆",
                    "問題・章・クイズで得るすべての学習報酬が13%増加",
                    210_000_000_000L, 0, 0L, "flawless_25",
                    List.of(fx("mastery_bonus", 13))));

    /**
     * 達成型アイテムの解放条件。画面のカードにそのまま出す。
     *
     * 下2つ（{@code review_200} / {@code flawless_25}）が重い条件。全574問の3分の1以上を
     * 復習するか、25問を無傷で連続クリアしないと届かない。
     */
    static final Map<String, String> ACHIEVEMENT_NOTES = Map.ofEntries(
            Map.entry("lucky_coin_draw", "問題または復習問題へ正解するたび、0.3%の確率で解放"),
            Map.entry("same_day_15", "同じ日に異なる15問を初クリアまたは復習で正解"),
            Map.entry("streak_7", "7日連続で学習"),
            Map.entry("quiz_streak_20",
                    "確認クイズの20問へ1度目の回答で連続正解、または復習で異なる20問に連続正解"),
            Map.entry("chapter_no_hint", "1つの章をヒントなしで初制覇、または復習で全問に再正解"),
            Map.entry("chapter_one_day", "1つの章を同じ日に初制覇、または同じ日に全問復習"),
            Map.entry("store_5", "店舗を5店まで広げる"),
            Map.entry("persistent_clear", "クリア済みの1問へ累計10回以上提出"),
            Map.entry("review_200", "復習で異なる200問に正解する"),
            Map.entry("flawless_25",
                    "ヒントなし・1回の提出で25問連続クリア、または復習で異なる25問に連続正解"));

    /**
     * アプリ画面を表示している間だけ動く自動営業設備。
     *
     * 率は「次の問題を初クリアしたときの売上」に対する1分あたりの割合。
     * 最上位でも5%/分、かつ次の★まで5問分が上限。
     * 上限までは最短で100分かかり、オフライン中は稼働しない。
     */
    static final List<CafeAutomation> AUTOMATION = List.of(
            new CafeAutomation("warming_pot", "保温ポット", "🫖",
                    "作り置きを少しずつ販売 · 学習1回分の0.5%/分", 16_000L, 1, 50),
            new CafeAutomation("self_service", "セルフサービス台", "🥤",
                    "会計を待たずに販売 · 学習1回分の0.9%/分", 72_000L, 2, 90),
            new CafeAutomation("order_kiosk", "注文キオスク", "🖥️",
                    "注文と決済を自動化 · 学習1回分の1.3%/分", 320_000L, 3, 130),
            new CafeAutomation("auto_brew_line", "自動抽出ライン", "⚙️",
                    "抽出工程を自動連携 · 学習1回分の1.7%/分", 1_400_000L, 4, 170),
            new CafeAutomation("unmanned_cafe", "自動会計システム", "💳",
                    "提供後の会計まで自動化 · 学習1回分の2.1%/分", 6_000_000L, 5, 210),
            new CafeAutomation("serving_robot", "配膳ロボット", "🤖",
                    "客席への提供も自動化 · 学習1回分の2.5%/分", 30_000_000L, 6, 250),
            new CafeAutomation("demand_ai", "AI需要予測", "📈",
                    "来店予測で作り置きを最適化 · 学習1回分の3%/分", 150_000_000L, 7, 300),
            new CafeAutomation("smart_store_control", "スマート店舗管制", "🛰️",
                    "全設備を一括制御 · 学習1回分の3.5%/分", 750_000_000L, 8, 350),
            new CafeAutomation("round_clock_cafe", "24時間無人店舗", "🌙",
                    "表示中の店舗運営を完全無人化 · 学習1回分の4%/分", 4_000_000_000L, 9, 400),
            new CafeAutomation("autonomous_cafe", "完全自律カフェ", "🦾",
                    "注文から提供まで自律運転 · 学習1回分の4.4%/分", 20_000_000_000L, 10, 440),
            new CafeAutomation("learning_grid", "学習グリッド管制", "🌐",
                    "世界の店舗を共同制御 · 学習1回分の4.7%/分", 100_000_000_000L, 11, 470),
            new CafeAutomation("mastery_ai", "マスタリー運営AI", "🧠",
                    "全店舗の注文を最適化 · 学習1回分の5%/分", 400_000_000_000L, 12, 500));

    /** その系統の指定ランクの設備。無ければ null。 */
    static CafeUpgrade upgradeAt(String effectType, int tier) {
        for (CafeUpgrade upgrade : UPGRADES) {
            if (upgrade.effectType().equals(effectType) && upgrade.tier() == tier) {
                return upgrade;
            }
        }
        return null;
    }

    /** 指定ランクの自動営業設備。無ければ null。 */
    static CafeAutomation automationAt(int tier) {
        for (CafeAutomation automation : AUTOMATION) {
            if (automation.tier() == tier) {
                return automation;
            }
        }
        return null;
    }

    /** ★の数に見合う店のレベル。最初のレベルは★0から。 */
    static CafeLevel levelFor(int stars) {
        CafeLevel current = LEVELS.get(0);
        for (CafeLevel level : LEVELS) {
            if (stars >= level.threshold()) {
                current = level;
            }
        }
        return current;
    }

    /**
     * 進捗ファイルに入っていたIDが、いまも存在するか。
     *
     * 教材や設備を入れ替えたときに、消えたIDを黙って持ち続けないための確認。
     */
    static boolean isKnownUpgrade(String id) {
        return UPGRADES.stream().anyMatch(u -> u.id().equals(id));
    }

    static boolean isKnownItem(String id) {
        return ITEMS.stream().anyMatch(item -> item.id().equals(id));
    }

    static boolean isKnownAutomation(String id) {
        return AUTOMATION.stream().anyMatch(item -> item.id().equals(id));
    }
}
