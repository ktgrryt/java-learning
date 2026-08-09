package jq.format;

/**
 * 1行へ圧縮された教材コードを、表示・編集しやすい複数行へ整える。
 *
 * <p>すでに複数行で書かれたコードには触れない。文字列、文字、コメント内の記号はそのままにし、
 * 通常コードの波括弧と、forヘッダー外のセミコロンだけを改行位置として使う。</p>
 */
public final class JavaSnippetFormatter {

    private static final String INDENT = "    ";

    private JavaSnippetFormatter() {
    }

    public static String formatIfCompact(String source) {
        if (source == null || source.isBlank() || source.lines().count() > 2) {
            return source;
        }

        StringBuilder out = new StringBuilder(source.length() + 128);
        int indent = 0;
        int parentheses = 0;
        State state = State.NORMAL;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (state == State.LINE_COMMENT) {
                out.append(c);
                if (c == '\n') {
                    state = State.NORMAL;
                    appendIndent(out, indent);
                }
                continue;
            }
            if (state == State.BLOCK_COMMENT) {
                out.append(c);
                if (c == '*' && next == '/') {
                    out.append('/');
                    i++;
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.STRING || state == State.CHARACTER) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(++i));
                } else if ((state == State.STRING && c == '"')
                        || (state == State.CHARACTER && c == '\'')) {
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.TEXT_BLOCK) {
                out.append(c);
                if (c == '"' && next == '"' && i + 2 < source.length()
                        && source.charAt(i + 2) == '"') {
                    out.append("\"\"");
                    i += 2;
                    state = State.NORMAL;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                appendSpaceIfNeeded(out);
                out.append("//");
                i++;
                state = State.LINE_COMMENT;
            } else if (c == '/' && next == '*') {
                appendSpaceIfNeeded(out);
                out.append("/*");
                i++;
                state = State.BLOCK_COMMENT;
            } else if (c == '"' && next == '"' && i + 2 < source.length()
                    && source.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 2;
                state = State.TEXT_BLOCK;
            } else if (c == '"') {
                out.append(c);
                state = State.STRING;
            } else if (c == '\'') {
                out.append(c);
                state = State.CHARACTER;
            } else if (c == '(') {
                parentheses++;
                out.append(c);
            } else if (c == ')') {
                parentheses = Math.max(0, parentheses - 1);
                out.append(c);
            } else if (c == '{') {
                trimTrailingSpace(out);
                out.append(" {");
                indent++;
                newline(out, indent);
            } else if (c == '}') {
                indent = Math.max(0, indent - 1);
                if (!atLineStart(out)) {
                    newline(out, indent);
                } else {
                    replaceIndent(out, indent);
                }
                out.append('}');
                char following = nextNonWhitespace(source, i + 1);
                if (following != ';' && following != ',' && following != ')' && following != ']') {
                    newline(out, indent);
                }
            } else if (c == ';' && parentheses == 0) {
                trimTrailingSpace(out);
                out.append(';');
                newline(out, indent);
            } else if (Character.isWhitespace(c)) {
                appendSpaceIfNeeded(out);
            } else {
                out.append(c);
            }
        }

        trimTrailingWhitespace(out);
        return out.append('\n').toString();
    }

    private static char nextNonWhitespace(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return source.charAt(i);
            }
        }
        return '\0';
    }

    private static void newline(StringBuilder out, int indent) {
        trimTrailingSpace(out);
        if (out.isEmpty() || out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        appendIndent(out, indent);
    }

    private static void appendIndent(StringBuilder out, int indent) {
        out.append(INDENT.repeat(Math.max(0, indent)));
    }

    private static void replaceIndent(StringBuilder out, int indent) {
        int lineStart = out.lastIndexOf("\n") + 1;
        out.setLength(lineStart);
        appendIndent(out, indent);
    }

    private static boolean atLineStart(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(out.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void appendSpaceIfNeeded(StringBuilder out) {
        if (!out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (!Character.isWhitespace(last)) {
                out.append(' ');
            }
        }
    }

    private static void trimTrailingSpace(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) != '\n'
                && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
    }

    private static void trimTrailingWhitespace(StringBuilder out) {
        while (!out.isEmpty() && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
    }

    private enum State {
        NORMAL, STRING, CHARACTER, TEXT_BLOCK, LINE_COMMENT, BLOCK_COMMENT
    }
}
