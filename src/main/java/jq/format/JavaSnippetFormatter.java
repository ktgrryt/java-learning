package jq.format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 1行へ圧縮された教材コードを、初心者がそのまま写して覚えられる書き方へ整える。
 *
 * <p>改行とインデントだけでなく、演算子・カンマ・キーワードのまわりの空白もそろえる。
 * 教材の模範解答は「読んで覚える見本」なので、`int n=s.nextInt();` のような詰まった書き方を
 * そのまま見せない。</p>
 *
 * <p>文字列・文字・テキストブロック・コメントの中身には触らない。
 * 変えるのは空白と改行だけで、トークンの並びは入力と完全に一致する
 * （`>>` をジェネリクスの閉じ2つへ分ける場合を除き、連結すれば同じ文字列になる）。</p>
 *
 * <p>すでに整った複数行コードへ使うと元の改行位置は失われるため、
 * 表示のときは {@link #formatIfCompact(String)} を使い、詰まったコードだけを対象にする。</p>
 */
public final class JavaSnippetFormatter {

    private static final String INDENT = "    ";

    /** `(` の前に空白を置くキーワード。メソッド呼び出しの `(` と区別するために要る。 */
    private static final Set<String> KEYWORD_BEFORE_PAREN = Set.of(
            "if", "for", "while", "switch", "try", "catch", "synchronized",
            "return", "throw", "do", "assert", "yield");

    /** 前後に空白を置く演算子。 */
    private static final Set<String> SPACED_OPERATORS = Set.of(
            "=", "==", "!=", "<=", ">=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "<<", ">>", ">>>", "<<=", ">>=", ">>>=",
            "->", "?", ":", "*", "/", "%", "&", "|", "^", "+", "-", "<", ">");

    /** `}` の直後で改行しないトークン。`} else {` や `} while (...);` のため。 */
    private static final Set<String> GLUED_AFTER_CLOSE = Set.of(
            ";", ",", ")", "]", ".", "else", "catch", "finally", "while");

    /** 長いものから順に照合する記号。`>>=` を `>` `>=` と切らないため順序が重要。 */
    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "<<", ">>",
            "<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "~", "&", "|", "^", "?", ":",
            ";", ",", ".", "(", ")", "[", "]", "{", "}", "@"};

    private JavaSnippetFormatter() {
    }

    /** 詰まったコードのときだけ整形する。すでに整っているコードはそのまま返す。 */
    public static String formatIfCompact(String source) {
        if (source == null || source.isBlank() || !isCompact(source)) {
            return source;
        }
        return format(source);
    }

    /**
     * 1行に複数の文やブロックが詰め込まれているか。
     *
     * <p>文字列やコメントの中の `;` `{` は数えない。カッコの内側の `;` も数えない
     * （`for (int i = 0; i < n; i++)` は1つの長い文であって、詰め込みではない）。</p>
     *
     * <p><b>ブロックの `{` が1行に2つ以上あれば、長さや文の数によらず詰め込みとみなす。</b>
     * `public class Main {public static void main(String[] args){/* TODO *&#47;}}` のように
     * <em>宣言だけ</em>を詰めたひな形は、文が0個なので長さの条件では拾えなかった
     * （実際に3件見落としていた）。配列初期化子とラムダの `{`
     * （直前が {@code = , { ( -> + ]} のもの）はブロックではないので数えない。</p>
     */
    public static boolean isCompact(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        if (indentUnitTooSmall(source)) {
            return true;
        }
        for (String line : blankOutLiterals(source).split("\n", -1)) {
            int statements = 0;
            int blocks = 0;
            int depth = 0;
            for (int k = 0; k < line.length(); k++) {
                switch (line.charAt(k)) {
                    case '(', '[' -> depth++;
                    case ')', ']' -> depth--;
                    case ';' -> {
                        if (depth <= 0) {
                            statements++;
                        }
                    }
                    case '{' -> blocks++;
                    default -> { }
                }
            }
            if (blockBraces(line) >= 2) {
                return true;
            }
            if (line.length() > 100 && (statements >= 2 || blocks >= 2)) {
                return true;
            }
            if (line.length() > 60 && blocks >= 2 && statements >= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 字下げの最小単位が4より小さいか（1〜3桁で字下げしているか）。
     *
     * <p>教材の字下げは4桁。1桁や2桁で詰めて書かれたコードは、`;` や `{` の数では
     * 拾えないのに読みにくい（ch22・ch23のServletのひな形が実際にそうだった）ので、
     * 詰め込みとして整形の対象にする。</p>
     */
    private static boolean indentUnitTooSmall(String source) {
        int smallest = Integer.MAX_VALUE;
        for (String line : source.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (indent > 0) {
                smallest = Math.min(smallest, indent);
            }
        }
        return smallest != Integer.MAX_VALUE && smallest < 4;
    }

    /** ブロックを開く `{` の数。配列初期化子・ラムダ・式の中のものは数えない。 */
    private static int blockBraces(String line) {
        int count = 0;
        for (int k = 0; k < line.length(); k++) {
            if (line.charAt(k) != '{') {
                continue;
            }
            String before = line.substring(0, k).stripTrailing();
            if (before.endsWith("=") || before.endsWith(",") || before.endsWith("{")
                    || before.endsWith("(") || before.endsWith("->") || before.endsWith("+")
                    || before.endsWith("]")) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** 改行位置とインデント、空白を作り直す。元の改行位置は保たない。 */
    public static String format(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        return new Emitter(tokenize(source)).run();
    }

    // ------------------------------------------------------------------ 字句解析

    private enum Kind { WORD, NUMBER, STRING, CHAR, TEXT_BLOCK, LINE_COMMENT, BLOCK_COMMENT, OP }

    private static final class Tok {
        final Kind kind;
        final String text;

        Tok(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        boolean is(String s) {
            return kind == Kind.OP && text.equals(s);
        }

        boolean isWord(String s) {
            return kind == Kind.WORD && text.equals(s);
        }
    }

    private static List<Tok> tokenize(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && peek(src, i + 1) == '/') {
                int j = i;
                while (j < n && src.charAt(j) != '\n') {
                    j++;
                }
                out.add(new Tok(Kind.LINE_COMMENT, src.substring(i, j).stripTrailing()));
                i = j;
            } else if (c == '/' && peek(src, i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int j = end < 0 ? n : end + 2;
                out.add(new Tok(Kind.BLOCK_COMMENT, src.substring(i, j)));
                i = j;
            } else if (c == '"' && peek(src, i + 1) == '"' && peek(src, i + 2) == '"') {
                int j = i + 3;
                while (j < n && !(src.charAt(j) == '"' && peek(src, j + 1) == '"' && peek(src, j + 2) == '"')) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 3);
                out.add(new Tok(Kind.TEXT_BLOCK, src.substring(i, j)));
                i = j;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.add(new Tok(c == '"' ? Kind.STRING : Kind.CHAR, src.substring(i, j)));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && isNumberPart(src, j)) {
                    j++;
                }
                out.add(new Tok(Kind.NUMBER, src.substring(i, j)));
                i = j;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(src.charAt(j))) {
                    j++;
                }
                // `non-sealed` はハイフンを含む唯一の修飾子。分けると別の意味になるので1語で扱う。
                if (src.startsWith("non-sealed", i)) {
                    j = i + "non-sealed".length();
                }
                out.add(new Tok(Kind.WORD, src.substring(i, j)));
                i = j;
            } else {
                String op = matchOperator(src, i);
                out.add(new Tok(Kind.OP, op));
                i += op.length();
            }
        }
        return out;
    }

    private static boolean isNumberPart(String src, int i) {
        char c = src.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
            // 1.5 や 0xFF、100L。ただし `list.get(0).x` の `.` で行き過ぎないよう、
            // `.` の次が数字でなければ数値の一部とみなさない。
            return c != '.' || (i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)));
        }
        // 3.4e-5 の符号
        return (c == '+' || c == '-') && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E');
    }

    private static String matchOperator(String src, int i) {
        for (String op : OPERATORS) {
            if (src.startsWith(op, i)) {
                return op;
            }
        }
        return src.substring(i, i + 1);
    }

    private static char peek(String src, int i) {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    // ------------------------------------------------------------------ 出力

    /**
     * いま開いているかたまり。`;` で改行するかどうかの判断に使う。
     *
     * <p>{@code BLOCK} の中の `;` は文の終わりなので改行する。{@code FOR_PAREN}
     * （`for (...)` と try-with-resources）と {@code ARRAY} の中の `;` は区切りなので改行しない。
     * `System.out.println(switch (x) { case ... -> ...; })` のように括弧の内側でも
     * ブロックが開いていれば改行が要るので、括弧とブロックを1つのスタックで持つ。</p>
     */
    private enum Group { BLOCK, ARRAY, PAREN, FOR_PAREN }

    private static final class Emitter {
        private final List<Tok> toks;
        private final StringBuilder out = new StringBuilder();
        private final Deque<Group> groups = new ArrayDeque<>();
        private int indent;
        private int parens;
        private int generics;
        private int i;
        /** 行コメントを含む注釈の、閉じかっこの次の位置。そこまで来たら改行する。 */
        private int annotationEnd = -1;
        /** 直前に出したのが前置の `++` `--` か（次のトークンとの間に空白を入れない）。 */
        private boolean afterPrefixStep;

        Emitter(List<Tok> toks) {
            this.toks = new ArrayList<>(toks);
        }

        String run() {
            while (i < toks.size()) {
                Tok t = toks.get(i);
                if (t.kind == Kind.OP) {
                    emitOperator(t);
                } else if (t.kind == Kind.LINE_COMMENT) {
                    // `{// TODO` と続けると `}` までコメントに入るので、必ず空白を挟む
                    if (!out.isEmpty() && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                        out.append(' ');
                    }
                    out.append(t.text);
                    newline();
                    i++;
                } else {
                    space();
                    out.append(t.text);
                    afterAnnotationBreak(t);
                    i++;
                }
            }
            while (!out.isEmpty() && Character.isWhitespace(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
            return out.append('\n').toString();
        }

        // -------------------------------------------------------------- 記号ごと

        private void emitOperator(Tok t) {
            switch (t.text) {
                case "{" -> openBrace();
                case "}" -> closeBrace();
                case ";" -> semicolon();
                case "," -> {
                    out.append(',');
                    i++;
                }
                case "(" -> {
                    if (needSpaceBeforeParen()) {
                        space();
                    }
                    out.append('(');
                    // `for (...)` と try-with-resources の中の `;` は区切りなので改行しない
                    Tok before = peekAt(-1);
                    boolean separatesWithSemicolons = before != null && before.kind == Kind.WORD
                            && (before.text.equals("for") || before.text.equals("try"));
                    groups.push(separatesWithSemicolons ? Group.FOR_PAREN : Group.PAREN);
                    parens++;
                    i++;
                }
                case ")" -> {
                    if (groups.peek() == Group.PAREN || groups.peek() == Group.FOR_PAREN) {
                        groups.pop();
                    }
                    parens = Math.max(0, parens - 1);
                    out.append(')');
                    i++;
                    if (i == annotationEnd) {          // 行コメントを含む注釈。ここで改行する
                        annotationEnd = -1;
                        newline();
                    }
                }
                case "[", "]", ".", "::" -> {
                    out.append(t.text);
                    i++;
                }
                case "..." -> {
                    out.append("...");
                    i++;
                }
                case "@" -> {
                    space();
                    out.append('@');
                    i++;
                }
                case "++", "--" -> {
                    if (isPrefixPosition()) {
                        space();
                        afterPrefixStep = true;      // `++ value` と離さない
                    }
                    out.append(t.text);
                    i++;
                }
                case "!", "~" -> {
                    space();
                    out.append(t.text);
                    i++;
                }
                case "<" -> angleOpen();
                case ">", ">>", ">>>" -> angleClose(t);
                case "?" -> question();
                default -> {
                    if (isUnary(t.text)) {
                        space();
                        out.append(t.text);
                    } else if (SPACED_OPERATORS.contains(t.text) && !afterDot()) {
                        space();
                        out.append(t.text);
                        out.append(' ');
                    } else {
                        out.append(t.text);
                    }
                    i++;
                }
            }
        }

        private void openBrace() {
            boolean array = isArrayInitializer();
            if (array) {
                space();
                out.append('{');
                groups.push(Group.ARRAY);
                i++;
                return;
            }
            // 空の本体は `{ }` と1行で出す（`record Point(int x, int y) { }`）
            if (peekAt(1) != null && peekAt(1).is("}")) {
                spaceUnlessLineStart();
                out.append("{ }");
                i += 2;
                memberEnd();
                return;
            }
            spaceUnlessLineStart();
            out.append('{');
            groups.push(Group.BLOCK);
            indent++;
            newline();
            i++;
        }

        private void closeBrace() {
            Group kind = Group.BLOCK;
            if (groups.peek() == Group.BLOCK || groups.peek() == Group.ARRAY) {
                kind = groups.pop();
            }
            if (kind == Group.ARRAY) {
                out.append('}');
                i++;
                return;
            }
            indent = Math.max(0, indent - 1);
            if (atLineStart()) {
                reindent();
            } else {
                newline();
            }
            out.append('}');
            i++;
            memberEnd();
        }

        /** ブロックを閉じた直後（i は `}` の次を指している）。改行と、メンバー間の空行。 */
        private void memberEnd() {
            Tok after = peekAt(0);
            if (after == null) {
                return;
            }
            if (after.kind == Kind.OP && GLUED_AFTER_CLOSE.contains(after.text)) {
                return;
            }
            if (after.kind == Kind.WORD && GLUED_AFTER_CLOSE.contains(after.text)) {
                out.append(' ');
                return;
            }
            newline();
            if (indent <= 1 && !after.is("}")) {
                blankLine();
            }
        }

        private void semicolon() {
            out.append(';');
            i++;
            Group inside = groups.peek();
            if (inside == Group.FOR_PAREN || inside == Group.ARRAY || inside == Group.PAREN) {
                out.append(' ');       // for ヘッダーや配列初期化子の中の区切り
                return;
            }
            newline();
            Tok after = peekAt(0);
            if (after == null) {
                return;
            }
            // import / package のかたまりの後、およびフィールドとメソッドの間に空行を置く
            if (indent == 0 && !after.isWord("import") && !after.isWord("package")
                    && isDeclarationLike(after)) {
                blankLine();
            } else if (indent == 1 && startsMemberWithBody()) {
                blankLine();
            }
        }

        private void angleOpen() {
            if (isGenericStart()) {
                generics++;
                out.append('<');
                i++;
                return;
            }
            space();
            out.append("< ");
            i++;
        }

        private void angleClose(Tok t) {
            if (generics > 0) {
                generics--;
                out.append('>');
                // `List<Future<Integer>>` の `>>` は閉じ2つ。残りを押し戻す。
                if (t.text.length() > 1) {
                    toks.set(i, new Tok(Kind.OP, t.text.substring(1)));
                } else {
                    i++;
                }
                return;
            }
            space();
            out.append(t.text).append(' ');
            i++;
        }

        private void question() {
            if (generics > 0) {
                out.append('?');
                i++;
                if (peekAt(0) != null && peekAt(0).kind == Kind.WORD) {
                    out.append(' ');
                }
                return;
            }
            space();
            out.append("? ");
            i++;
        }

        /** アノテーションの引数が閉じた直後、宣言が続くなら改行する。 */
        private void afterAnnotationBreak(Tok word) {
            if (parens != 0 || generics != 0) {
                return;
            }
            if (!(previousIs("@"))) {
                return;
            }
            if (word.isWord("interface")) {
                return;                // `@interface Command` は注釈の宣言。名前を続けて置く
            }
            int j = i + 1;
            if (j < toks.size() && toks.get(j).is("(")) {
                int depth = 0;
                while (j < toks.size()) {
                    Tok t = toks.get(j);
                    if (t.is("(")) {
                        depth++;
                    } else if (t.is(")")) {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    j++;
                }
                j++;
            } else {
                j = i + 1;
            }
            Tok after = j < toks.size() ? toks.get(j) : null;
            if (after == null || !isDeclarationLike(after)) {
                return;
            }
            // 引数に行コメントがある注釈は1行へ畳めない（`{// TODO}` になり `}` まで
            // コメントに飲まれる）。畳まず、閉じかっこの次で改行するだけにする。
            for (int k = i + 1; k < j; k++) {
                if (toks.get(k).kind == Kind.LINE_COMMENT) {
                    annotationEnd = j;
                    return;
                }
            }
            // `@Code(200) OK,` のように名前だけで終わるものは enum 定数。定数は横に並べる
            Tok afterName = j + 1 < toks.size() ? toks.get(j + 1) : null;
            if (after.kind == Kind.WORD && afterName != null
                    && (afterName.is(",") || afterName.is(";") || afterName.is("}"))) {
                return;
            }
            // アノテーション本体を出し切ってから改行する
            while (i + 1 < j) {
                i++;
                Tok t = toks.get(i);
                if (t.kind == Kind.OP) {
                    emitOperatorInline(t);
                } else {
                    space();
                    out.append(t.text);
                }
            }
            newline();
        }

        /** アノテーション引数の中だけを出す簡易版（改行判定を挟まない）。 */
        private void emitOperatorInline(Tok t) {
            switch (t.text) {
                case "(" -> out.append('(');
                case ")" -> out.append(')');
                case "," -> out.append(", ");
                case "." -> out.append('.');
                case "=" -> out.append(" = ");
                case "{" -> out.append('{');
                case "}" -> out.append('}');
                default -> out.append(t.text);
            }
        }

        // -------------------------------------------------------------- 判定

        /** `<` がジェネリクスの開きか。閉じ `>` まで型引数として成り立つ形なら開き。 */
        private boolean isGenericStart() {
            Tok prev = previous();
            if (prev == null || prev.kind != Kind.WORD) {
                return false;
            }
            int depth = 0;
            for (int j = i; j < toks.size(); j++) {
                Tok t = toks.get(j);
                if (t.kind == Kind.WORD) {
                    if (!t.text.equals("extends") && !t.text.equals("super")
                            && !Character.isJavaIdentifierStart(t.text.charAt(0))) {
                        return false;
                    }
                    continue;
                }
                if (t.kind != Kind.OP) {
                    return false;
                }
                switch (t.text) {
                    case "<" -> depth++;
                    case ">" -> {
                        depth--;
                        if (depth == 0) {
                            return true;
                        }
                    }
                    case ">>", ">>>" -> {
                        // `List<Future<Integer>>` の `>>` は閉じ2つ。
                        // 内側の `<` から見ると自分の分を越えて閉じるので、0以下なら閉じられている。
                        depth -= t.text.length();
                        if (depth <= 0) {
                            return true;
                        }
                    }
                    case ",", ".", "[", "]" -> { }
                    case "?" -> {
                        // ワイルドカードは `<` か `,` の直後にだけ現れる
                        Tok before = toks.get(j - 1);
                        if (!before.is("<") && !before.is(",")) {
                            return false;
                        }
                    }
                    default -> {
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean isArrayInitializer() {
            Tok prev = previous();
            if (prev == null) {
                return false;
            }
            if (prev.kind == Kind.OP) {
                return switch (prev.text) {
                    case "=", ",", "{", "(", "]" -> true;
                    default -> false;
                };
            }
            return false;
        }

        /** `(` の前に空白が必要か。`if (` は要る、`println(` は要らない。 */
        private boolean needSpaceBeforeParen() {
            Tok prev = previous();
            return prev != null && prev.kind == Kind.WORD
                    && KEYWORD_BEFORE_PAREN.contains(prev.text);
        }

        /** `++` `--` が前置か。`i++` と `++i` を区別する。 */
        private boolean isPrefixPosition() {
            Tok prev = previous();
            if (prev == null) {
                return true;
            }
            // `return ++value` の `return` は変数ではない。keywordの後ろは前置
            if (prev.kind == Kind.WORD && KEYWORD_BEFORE_PAREN.contains(prev.text)) {
                return true;
            }
            if (prev.kind == Kind.WORD || prev.kind == Kind.NUMBER) {
                return false;
            }
            return !(prev.is(")") || prev.is("]"));
        }

        /** `-1` の `-` のように、単項として使われているか。 */
        private boolean isUnary(String op) {
            if (!op.equals("-") && !op.equals("+")) {
                return false;
            }
            Tok prev = previous();
            if (prev == null) {
                return true;
            }
            if (prev.kind == Kind.WORD) {
                // `return -1` は単項、`a - 1` は二項
                return switch (prev.text) {
                    case "return", "case", "yield", "new", "instanceof" -> true;
                    default -> false;
                };
            }
            if (prev.kind == Kind.NUMBER || prev.kind == Kind.STRING || prev.kind == Kind.CHAR) {
                return false;
            }
            return !(prev.is(")") || prev.is("]") || prev.is("++") || prev.is("--"));
        }

        /** `import java.util.*;` の `*` のように、`.` の直後か。 */
        private boolean afterDot() {
            Tok prev = previous();
            return prev != null && prev.is(".");
        }

        /**
         * 宣言のはじまりに見えるトークンか。アノテーションの後の改行と、空行の判断に使う。
         *
         * <p>修飾子キーワードのほか、大文字で始まる語も型名として扱う
         * （`@Inject PriceService(...)` のようにコンストラクタが続く形のため）。</p>
         */
        private boolean isDeclarationLike(Tok t) {
            if (t.is("@")) {
                return true;
            }
            if (t.kind != Kind.WORD) {
                return false;
            }
            boolean modifier = switch (t.text) {
                case "public", "private", "protected", "static", "final", "abstract",
                     "class", "interface", "enum", "record", "sealed", "non-sealed", "void",
                     "default", "synchronized", "native", "strictfp" -> true;
                default -> false;
            };
            return modifier || Character.isUpperCase(t.text.charAt(0));
        }

        /** これから始まるのが本体つきのメンバー（メソッド・コンストラクタ）か。 */
        private boolean startsMemberWithBody() {
            int depth = 0;
            for (int j = i; j < toks.size(); j++) {
                Tok t = toks.get(j);
                if (t.kind != Kind.OP) {
                    continue;
                }
                switch (t.text) {
                    case "(", "[" -> depth++;
                    case ")", "]" -> depth--;
                    case ";" -> {
                        if (depth == 0) {
                            return false;
                        }
                    }
                    case "{" -> {
                        if (depth == 0) {
                            return true;
                        }
                    }
                    case "}" -> {
                        if (depth == 0) {
                            return false;
                        }
                    }
                    default -> { }
                }
            }
            return false;
        }

        private Tok previous() {
            return i > 0 ? toks.get(i - 1) : null;
        }

        private boolean previousIs(String op) {
            Tok prev = previous();
            return prev != null && prev.is(op);
        }

        /** 現在位置から k 個先のトークン。範囲外は null。 */
        private Tok peekAt(int k) {
            int j = i + k;
            return j >= 0 && j < toks.size() ? toks.get(j) : null;
        }

        // -------------------------------------------------------------- 出力補助

        /**
         * 必要なら空白を1つ置く。
         *
         * <p>直前が「後ろに空白を置かない記号」なら何もしない。`<` はジェネリクスの開きのとき
         * だけ空白なしで出るので、ここに含めてよい（比較の `<` は自分で空白を付けている）。
         * `:` も同様で、`::` は空白なし、三項や拡張forの `:` は自分で空白を付けている。
         * `{` は配列初期化子（`{1, 2, 3}`）のときだけ後ろに文が続く。ブロックなら直後に改行する。</p>
         */
        private void space() {
            if (afterPrefixStep) {
                afterPrefixStep = false;
                return;
            }
            if (out.isEmpty()) {
                return;
            }
            char last = out.charAt(out.length() - 1);
            if (!Character.isWhitespace(last) && last != '(' && last != '[' && last != '@'
                    && last != '.' && last != '!' && last != '~' && last != '<' && last != ':'
                    && last != '{') {
                out.append(' ');
            }
        }

        private void spaceUnlessLineStart() {
            if (!atLineStart()) {
                space();
            }
        }

        private void newline() {
            trimTrailingSpaces();
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(INDENT.repeat(indent));
        }

        private void blankLine() {
            trimTrailingSpaces();
            if (out.isEmpty()) {
                return;
            }
            out.append('\n').append(INDENT.repeat(indent));
        }

        private void reindent() {
            int lineStart = out.lastIndexOf("\n") + 1;
            out.setLength(lineStart);
            out.append(INDENT.repeat(indent));
        }

        private boolean atLineStart() {
            for (int j = out.length() - 1; j >= 0 && out.charAt(j) != '\n'; j--) {
                if (!Character.isWhitespace(out.charAt(j))) {
                    return false;
                }
            }
            return true;
        }

        private void trimTrailingSpaces() {
            while (!out.isEmpty() && out.charAt(out.length() - 1) != '\n'
                    && Character.isWhitespace(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
        }
    }

    // ------------------------------------------------------------------ 補助

    /** 文字列・文字・テキストブロック・コメントの中身を空白へ置き換える（長さと改行は保つ）。 */
    static String blankOutLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && peek(source, i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && peek(source, i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                while (i < stop) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '"' && peek(source, i + 1) == '"' && peek(source, i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n && !(source.charAt(i) == '"' && peek(source, i + 1) == '"'
                        && peek(source, i + 2) == '"')) {
                    int step = source.charAt(i) == '\\' ? 2 : 1;
                    for (int k = 0; k < step && i < n; k++) {
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
                for (int k = 0; k < 3 && i < n; k++) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    int step = source.charAt(i) == '\\' ? 2 : 1;
                    for (int k = 0; k < step && i < n; k++) {
                        out.append(' ');
                        i++;
                    }
                }
                if (i < n && source.charAt(i) == c) {
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

    /** トークンの並びを連結した文字列。整形前後で一致することを確かめるために使う。 */
    public static String tokenSignature(String source) {
        StringBuilder sb = new StringBuilder();
        for (Tok t : tokenize(source)) {
            sb.append(t.text);
        }
        return sb.toString();
    }
}
