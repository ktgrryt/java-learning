package jq.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 選択式の確認クイズ1問。
 *
 * 「このコードはコンパイルできるか」のように、コードを書かせる形では出題できない
 * 知識を問うために使う。正解の番号と解説は答え合わせをするまでブラウザへ渡さない
 * （{@link #toPublicJson()} が落とす）。判定は必ずサーバ側で行う。
 */
public record Quiz(String question, List<String> choices, int answer, String explanation) {

    /** ブラウザへ渡す表現。正解と解説は含めない。 */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        m.put("choices", new ArrayList<>(choices));
        return m;
    }
}
