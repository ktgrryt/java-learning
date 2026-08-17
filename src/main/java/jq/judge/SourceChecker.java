package jq.judge;

import jq.content.SourceCheck;
import jq.format.JavaText;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/** 学習対象の構文がソース中にあるかを、コメントやリテラルを除外して検査する。 */
public final class SourceChecker {

    private SourceChecker() {
    }

    public static List<String> failures(List<SourceCheck> checks, String source) {
        if (checks.isEmpty()) {
            return List.of();
        }
        String code = codeOnly(source);
        List<String> failures = new ArrayList<>();
        for (SourceCheck check : checks) {
            // 正規表現はコンテンツ読み込み時にコンパイル済み（SourceCheck.of）
            Matcher matcher = check.pattern().matcher(code);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count < check.minimum()
                    || (check.maximum() >= 0 && count > check.maximum())) {
                failures.add(check.message());
            }
        }
        return List.copyOf(failures);
    }

    /**
     * コメント、文字列、文字リテラルを同じ長さの空白へ置き換える。
     *
     * 実装は {@link JavaText#blankOutCommentsAndStrings(String)} に1つだけ置いてある
     * （クラス名の検出と採点で解釈がずれないように）。名前はここで受け続ける ―
     * {@code tools/CheckCount} が採点と同じ数え方を確かめるために呼んでいる。
     */
    static String codeOnly(String source) {
        return JavaText.blankOutCommentsAndStrings(source);
    }
}
