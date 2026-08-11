package jq.judge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sourceChecks の正規表現が模範解答に何回当たるかを、アプリと同じ条件で数える検証用ツール。
 *
 * <p>{@link SourceChecker#codeOnly(String)} と {@code MULTILINE | DOTALL} を採点と同じに
 * そろえてあるので、ここで出た回数は提出時の判定と一致する。Pythonで正規表現を再実装すると
 * Javaの正規表現との差で食い違うため、本物を呼ぶ。
 *
 * <p>標準入力は {@code パターン  ソース} を1件として、件と件のあいだを
 * {@code } で区切る。標準出力へ1件1行で回数を返す。
 */
public final class CheckCount {

    private CheckCount() {
    }

    public static void main(String[] args) throws IOException {
        String all = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String record : all.split("")) {
            if (record.isEmpty()) {
                continue;
            }
            int split = record.indexOf('');
            String pattern = record.substring(0, split);
            String source = record.substring(split + 1);
            Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL)
                    .matcher(SourceChecker.codeOnly(source));
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            out.append(count).append('\n');
        }
        System.out.print(out);
    }
}
