package jq.judge;

import jq.content.SourceCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Pattern pattern = Pattern.compile(check.pattern(), Pattern.MULTILINE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(code);
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

    /** コメント、文字列、文字リテラルを同じ長さの空白へ置き換える。 */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length()
                        && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && next == '"' && i + 2 < source.length()
                    && source.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < source.length() && !textBlockEnd(source, i)) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
                if (i < source.length()) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < source.length() && source.charAt(i) != quote
                        && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
                if (i < source.length() && source.charAt(i) == quote) {
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

    private static boolean textBlockEnd(String source, int i) {
        return source.charAt(i) == '"' && i + 2 < source.length()
                && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"';
    }
}
