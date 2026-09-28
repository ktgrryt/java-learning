package jq.content;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 出力だけでは確認できない、学習対象の書き方に関する軽量な検査。
 *
 * <p>正規表現はコメントと文字・文字列リテラルを除いたソースへ適用する。これはJavaの
 * 完全な構文解析ではなく、指定した構文を実際に一度は書いてもらうための補助検査である。
 * {@code fields} 型はJDKの構文木からクラス直下のフィールドを数え、
 * 変数名・修飾子・ローカル変数に左右されない構造検査を行う。</p>
 *
 * <p>正規表現はコンテンツを読み込むときに1回だけコンパイルして持っておく。
 * 提出のたびにコンパイルし直す必要はなく、書き間違いも起動時（コンテンツ読み込み時）に
 * 分かる。</p>
 *
 * @param pattern 検索する正規表現（コンパイル済み）
 * @param minimum 必要な出現回数
 * @param maximum 許容する最大回数。上限を設けない場合は -1
 * @param message 条件を満たさなかったとき学習者へ見せる説明
 * @param type regex（既定）またはfields。fieldsのpatternは対象クラス名の正規表現
 */
public record SourceCheck(Pattern pattern, int minimum, int maximum, String message, String type) {

    public SourceCheck(Pattern pattern, int minimum, int maximum, String message) {
        this(pattern, minimum, maximum, message, "regex");
    }

    public SourceCheck {
        if (!"regex".equals(type) && !"fields".equals(type)) {
            throw new IllegalArgumentException("sourceChecks の type は regex / fields にしてください");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("sourceChecks の pattern が空です");
        }
        if (minimum < 0 || (maximum >= 0 && maximum < minimum)) {
            throw new IllegalArgumentException("sourceChecks の回数指定が不正です");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("sourceChecks の message が空です");
        }
    }

    /**
     * コンテンツに書かれた正規表現から作る。
     *
     * 検査はコメントと文字・文字列リテラルを空白に置き換えたソースへ複数行のまま
     * 当てるので、{@code MULTILINE} と {@code DOTALL} は常に付ける。
     */
    public static SourceCheck of(String pattern, int minimum, int maximum, String message) {
        return of(pattern, minimum, maximum, message, "regex");
    }

    public static SourceCheck of(String pattern, int minimum, int maximum, String message, String type) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("sourceChecks の pattern が空です");
        }
        try {
            return new SourceCheck(
                    Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL),
                    minimum, maximum, message, type);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("sourceChecks の正規表現が不正です: " + pattern, e);
        }
    }
}
