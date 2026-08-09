package jq.content;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 出力だけでは確認できない、学習対象の書き方に関する軽量な検査。
 *
 * <p>正規表現はコメントと文字・文字列リテラルを除いたソースへ適用する。これはJavaの
 * 完全な構文解析ではなく、指定した構文を実際に一度は書いてもらうための補助検査である。</p>
 *
 * @param pattern 検索する正規表現
 * @param minimum 必要な出現回数
 * @param maximum 許容する最大回数。上限を設けない場合は -1
 * @param message 条件を満たさなかったとき学習者へ見せる説明
 */
public record SourceCheck(String pattern, int minimum, int maximum, String message) {

    public SourceCheck {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("sourceChecks の pattern が空です");
        }
        if (minimum < 0 || (maximum >= 0 && maximum < minimum)) {
            throw new IllegalArgumentException("sourceChecks の回数指定が不正です");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("sourceChecks の message が空です");
        }
        try {
            Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("sourceChecks の正規表現が不正です: " + pattern, e);
        }
    }
}
