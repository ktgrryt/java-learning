package jq.format;

/**
 * Javaソースの字句を、位置を保ったまま扱う道具。
 *
 * <p>{@link JavaSnippetFormatter} がトークン列を作るのに対し、こちらは<b>長さと行を崩さずに
 * 中身だけ消す</b>ための入口である。用途が2つある。</p>
 *
 * <ul>
 *   <li>{@code jq.runner.JavaRunner} … コメントやテキストブロックに書かれた
 *       {@code public class Foo} を本物の宣言と間違えないようにする</li>
 *   <li>{@code jq.judge.SourceChecker} … 学習対象の構文を数えるとき、
 *       コメントや文字列の中の記述を数えないようにする</li>
 * </ul>
 *
 * <p>この2つは同じ処理を別々に持っていた（各60行のほぼ同一の写し）。片方だけ直すと
 * 「クラス名の検出」と「採点」で解釈がずれ、しかも<b>ずれても両方コンパイルは通る</b>ため
 * 気づけない。実装はここ1つに寄せる。</p>
 */
public final class JavaText {

    private JavaText() {
    }

    /**
     * コメントと文字列リテラル（テキストブロックを含む）の中身を空白に置き換える。
     *
     * 文字数と改行位置はそのまま保つので、置き換えたあとの位置は元のソースと一致する。
     * 解説用のコメントやテキストブロックに書いた {@code public class Foo} を
     * 本物の宣言と間違えないようにするための前処理。
     */
    public static String blankOutCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < n && code.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(code.charAt(i) == '*' && i + 1 < n && code.charAt(i + 1) == '/')) {
                    out.append(code.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && next == '"' && i + 2 < n && code.charAt(i + 2) == '"') {
                // テキストブロック
                out.append("   ");
                i += 3;
                while (i < n && !isTextBlockEnd(code, i)) {
                    if (code.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(code.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && code.charAt(i) != quote && code.charAt(i) != '\n') {
                    if (code.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n && code.charAt(i) == quote) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isTextBlockEnd(String code, int i) {
        return code.charAt(i) == '"'
                && i + 2 < code.length()
                && code.charAt(i + 1) == '"'
                && code.charAt(i + 2) == '"';
    }
}
