import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jq.format.JavaSnippetFormatter;

/**
 * 標準入力のNUL区切りスニペットを整形し、NUL区切りで返す。
 *
 * tools/format_content_code.py から呼ばれる橋渡し。整形そのものは
 * {@link JavaSnippetFormatter}（アプリが表示に使うのと同じもの）に任せる。
 *
 * 各レコードは1行目が検査結果のヘッダーで、2行目以降が整形後のコード。
 *   @@JQFMT tokens=same idempotent=yes
 *
 * tokens は整形前後でトークン列が一致したか（＝空白と改行以外は変えていないか）、
 * idempotent はもう一度整形しても結果が変わらないか。
 */
public final class FormatContentCode {

    private FormatContentCode() {
    }

    public static void main(String[] args) throws IOException {
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String[] snippets = input.split("\0", -1);
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < snippets.length; i++) {
            if (i > 0) {
                out.append('\0');
            }
            String source = snippets[i];
            String formatted = JavaSnippetFormatter.format(source);
            boolean sameTokens = JavaSnippetFormatter.tokenSignature(source)
                    .equals(JavaSnippetFormatter.tokenSignature(formatted));
            boolean idempotent = JavaSnippetFormatter.format(formatted).equals(formatted);

            out.append("@@JQFMT tokens=").append(sameTokens ? "same" : "DIFFERENT")
               .append(" idempotent=").append(idempotent ? "yes" : "NO")
               .append('\n')
               .append(formatted);
        }

        System.out.write(out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
