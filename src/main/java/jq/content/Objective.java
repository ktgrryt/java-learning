package jq.content;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 章の到達目標1つ。「この章を終えると何ができるようになるか」を1文で書く。
 *
 * <p>章クリアは「必須問題を全部解いたか」を、{@code layers} は「概念・コード・実践のどこまで
 * 到達したか」を答える。どちらも<b>終わったか</b>の話なので、<b>何ができるようになるか</b>を
 * 表すのがこの型である。
 *
 * <p>文は観察できる形（「〜できる」「〜書ける」）で書く。「〜を理解する」は測れないので使わない。
 * 宣言した目標がその章の問題・クイズで測られているかは {@code tools/check-objectives.sh} が見張る。
 *
 * @param id   「章のid + {@code -oM}」形式（{@code ch03-o1}、旧形式の章なら {@code 30-o1}）。
 *             レッスンと問題の {@code objectiveIds} から参照される
 * @param text 学習者へ見せる1文
 */
public record Objective(String id, String text) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("text", text);
        return m;
    }
}
