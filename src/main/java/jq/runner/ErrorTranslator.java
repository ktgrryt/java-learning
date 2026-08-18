package jq.runner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * javac / JVM のエラーを初心者向けの日本語ヒントに置き換える。
 *
 * 「何が起きたか」より「次に何を直せばいいか」を書くことを優先する。
 *
 * 判定には {@code javax.tools.Diagnostic#getCode()} の診断コードを使う。
 * javac のメッセージ本文は実行環境のロケールで変わってしまうため、
 * 文字列マッチだけに頼ると英語環境で効かなくなる。診断コードは変わらない。
 * メッセージ本文は、記号名や変数名のような「細部」を拾うためだけに見る
 * （引用符に囲まれたトークンや識別子はロケールに関係なく同じ形で現れる）。
 */
public final class ErrorTranslator {

    private ErrorTranslator() {
    }

    /** メッセージ中の 'x' のように引用符で囲まれたトークン。日本語でも英語でも同じ形で出る。 */
    private static final Pattern QUOTED = Pattern.compile("'([^']{1,20})'");

    /** クラス名らしい形（大文字始まり）。import を勧めるかどうかの入口の判定に使う。 */
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Z][A-Za-z0-9_]*");

    /**
     * import が要るクラスを探すパッケージ。<b>先に見つかったものを使う</b>ので並び順が意味を持つ
     * （`java.util.Date` と `java.sql.Date` のように同名のクラスがある）。教材が使うものを、
     * 基礎編から順に並べてある。{@code java.lang} は import が要らないので入れない。
     *
     * <p>表にクラス名を持たず、JDKへ {@code Class.forName} で問い合わせる。教材が新しい
     * クラスを使い始めても書き足す必要がなく、パッケージ名を書き間違えたまま
     * 「`import java.util.LocalDate;`」と勧めてしまうこともない。
     */
    private static final String[] IMPORTABLE_PACKAGES = {
        "java.util", "java.util.function", "java.util.stream",
        "java.time", "java.time.format", "java.time.temporal",
        "java.io", "java.nio.file", "java.nio.charset",
        "java.math", "java.util.regex", "java.text",
        "java.util.concurrent", "java.util.concurrent.atomic", "java.sql",
    };

    /**
     * コンパイルエラーへのヒント。該当がなければ空文字を返す（UIは元のメッセージだけを出す）。
     *
     * @param code    診断コード（例 {@code compiler.err.expected}）
     * @param message javac のメッセージ本文
     */
    public static String forCompileError(String code, String message) {
        if (code == null) {
            code = "";
        }
        String msg = message == null ? "" : message;

        switch (code) {
            case "compiler.err.expected", "compiler.err.expected1", "compiler.err.expected2",
                 "compiler.err.expected3" -> {
                return forMissingToken(msg);
            }
            case "compiler.err.expected4" -> {
                return "波かっこ } が多すぎるようです。クラスの外にコードがはみ出していないか、"
                        + "{ と } の数が合っているか数えてみましょう。";
            }
            case "compiler.err.premature.eof" -> {
                return "コードが途中で終わっています。閉じ忘れた } や \" がないか、"
                        + "いちばん下から順に確かめてみましょう。";
            }
            case "compiler.err.cant.resolve", "compiler.err.cant.resolve.args",
                 "compiler.err.cant.resolve.location", "compiler.err.cant.resolve.location.args" -> {
                String name = symbolName(msg);
                String importable = importPackageOf(name);
                if (importable != null) {
                    return "「" + name + "」を使うには import が必要です。いちばん上（`class` より前）へ"
                            + " `import " + importable + "." + name + ";` を書き足しましょう。";
                }
                String subject = name != null ? "「" + name + "」" : "この名前";
                return subject + " が見つかりません。つづりが合っているか、使う前に値を用意できているか、"
                        + "大文字と小文字が合っているかを確かめましょう（Javaは大文字と小文字を区別します）。";
            }
            case "compiler.err.prob.found.req", "compiler.err.prob.found.req.1" -> {
                if (msg.contains("double") && msg.contains("int")) {
                    return "小数（double）を整数（int）にそのままは入れられません。"
                            + "小数のまま使うなら左側を `double` にし、整数として使うなら右側を整数にしましょう。";
                }
                if (msg.contains("String") && msg.contains("int")) {
                    return "文字列と整数は別の型です。`\"123\"` は文字列、`123` は整数です。"
                            + "クォートの有無と、左側に書いた型を確かめましょう。";
                }
                return "型が合っていません。左側の箱の型と、右側の値の型が同じか確かめましょう。";
            }
            case "compiler.err.var.might.not.have.been.initialized" -> {
                return "変数を宣言しただけで、まだ値を入れていません。使う前に値を代入しましょう。"
                        + "例: `int sum = 0;`";
            }
            case "compiler.err.already.defined" -> {
                return "同じ名前をもう一度宣言しています。2回目は型（`int` など）を書かず、"
                        + "代入だけにしましょう。例: 2行目は `n = 2;`";
            }
            case "compiler.err.missing.ret.stmt" -> {
                return "戻り値のあるメソッドなのに `return` がありません。"
                        + "どの分岐を通っても必ず `return` するようにしましょう。";
            }
            case "compiler.err.unclosed.str.lit" -> {
                return "文字列のダブルクォート \" が閉じられていません。\" は必ず2つで挟みます。";
            }
            case "compiler.err.unclosed.char.lit" -> {
                return "シングルクォート ' が閉じられていません。"
                        + "文字列には ' ではなくダブルクォート \" を使います（' は1文字だけのとき）。";
            }
            case "compiler.err.unclosed.comment" -> {
                return "コメントの `/*` が `*/` で閉じられていません。";
            }
            case "compiler.err.operator.cant.be.applied", "compiler.err.operator.cant.be.applied.1" -> {
                String op = firstQuoted(msg);
                String opText = op != null ? "演算子 `" + op + "` " : "その演算子";
                if (msg.contains("String")) {
                    return opText + "はこの文字列には使えません。記号と左右の値の型を確かめましょう。";
                }
                return opText + "はこの型どうしでは使えません。左右の値の型を確かめましょう。";
            }
            case "compiler.err.non-static.cant.be.ref" -> {
                return "`main` は static なので、static でないものを直接は使えません。"
                        + "そのメソッドや変数に `static` を付けるか、`new` でオブジェクトを作ってから使いましょう。";
            }
            case "compiler.err.cant.apply.symbol", "compiler.err.cant.apply.symbols" -> {
                return "メソッドに渡した引数の数か型が合っていません。カッコの中身を見直しましょう。";
            }
            case "compiler.err.unreachable.stmt" -> {
                return "`return` や `break` の後ろに、絶対に実行されないコードがあります。";
            }
            case "compiler.err.not.stmt" -> {
                return "文として成り立っていません。`=` の左に変数名があるか、"
                        + "`int = 5;` のように名前を書き忘れていないか確かめましょう。";
            }
            case "compiler.err.illegal.start.of.expr", "compiler.err.illegal.start.of.type" -> {
                return "式の書き方が崩れています。ひとつ上の行に `;` や `}` の抜けがないか確かめましょう。";
            }
            case "compiler.err.class.public.should.be.in.file" -> {
                // このアプリは「最初に見つかった public 型」の名前でファイルを保存するので、
                // クラス名が Main でなくても通る。ここに来るのは public な型が2つ以上あるとき。
                return "1つのファイルに `public` なクラスは1つだけ置けます。"
                        + "補助のクラスからは `public` を外しましょう"
                        + "（`public class Dog` → `class Dog`）。";
            }
            case "compiler.err.doesnt.exist" -> {
                return "その `import` のパッケージが見つかりません。つづりを確かめましょう。"
                        + "Scanner なら `import java.util.Scanner;` です。";
            }
            case "compiler.err.cant.deref" -> {
                return "`int` や `double` のような基本の型には `.` でメソッドを呼べません。";
            }
            case "compiler.err.void.not.allowed.here" -> {
                return "何も返さない（void の）メソッドの結果を使おうとしています。";
            }
            case "compiler.err.no.suitable.method.found" -> {
                return "その名前のメソッドはありますが、渡した引数に合うものがありません。引数の型と数を見直しましょう。";
            }
            case "compiler.err.invalid.meth.decl.ret.type.req" -> {
                return "メソッドの戻り値の型がありません。`void` か `int` などを名前の前に書きます。";
            }
            case "compiler.err.abstract.cant.be.instantiated",
                 "compiler.err.new.abstract.cant.be.instantiated" -> {
                return "抽象クラスやインタフェースは `new` で直接作れません。";
            }
            default -> {
                return "";
            }
        }
    }

    /** `';' expected` 系のヒント。足りない記号ごとに言い方を変える。 */
    private static String forMissingToken(String message) {
        String token = firstQuoted(message);
        if (token == null) {
            return "記号が足りません。この行とひとつ上の行を見比べてみましょう。";
        }
        return switch (token) {
            case ";" -> "文の終わりのセミコロン `;` が足りません。"
                    + "この行か、ひとつ上の行の末尾に `;` を足してみましょう。";
            case ")" -> "閉じカッコ `)` が足りません。`(` と `)` の数が同じか数えてみましょう。";
            case "(" -> "開きカッコ `(` が足りません。";
            case "}" -> "閉じ波かっこ `}` が足りません。`{` と `}` の数が同じか数えてみましょう。";
            case "{" -> "開き波かっこ `{` が足りません。";
            case "]" -> "閉じ角かっこ `]` が足りません。";
            case "=" -> "代入の `=` が足りません。";
            default -> "`" + token + "` が足りません。この行の書き方を見直しましょう。";
        };
    }

    /** 実行時例外（stderr）へのヒント。 */
    public static String forRuntimeError(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return "";
        }
        if (stderr.contains("ArrayIndexOutOfBoundsException")) {
            return "配列の範囲外を見ています。添字は `0` から `長さ - 1` までです。"
                    + "ループの条件が `<=` になっていないか確かめましょう。";
        }
        if (stderr.contains("StringIndexOutOfBoundsException")) {
            return "文字列の範囲外を見ています。`charAt` の添字は `0` から `length() - 1` までです。";
        }
        if (stderr.contains("NullPointerException")) {
            return "まだ何も入っていない（null の）変数を使おうとしています。"
                    + "`new` や代入を忘れていないか確かめましょう。";
        }
        if (stderr.contains("ArithmeticException") && stderr.contains("/ by zero")) {
            return "0 で割り算をしています。割る数が 0 でないかを先に確かめましょう。";
        }
        if (stderr.contains("NumberFormatException")) {
            return "数値に変換できない文字列を変換しようとしています。"
                    + "余分な空白や文字が混ざっていないか確かめましょう。";
        }
        if (stderr.contains("InputMismatchException")) {
            return "入力の形が想定と違います。`nextInt()` で読むところに数値以外が来ていませんか。";
        }
        if (stderr.contains("NoSuchElementException")) {
            return "読む入力が足りません。`Scanner` で読む回数と、与えられた入力の数が合っているか確かめましょう。";
        }
        if (stderr.contains("StackOverflowError")) {
            return "メソッドが自分自身を呼び続けています。再帰が止まる条件があるか確かめましょう。";
        }
        if (stderr.contains("OutOfMemoryError")) {
            return "メモリを使い切りました。ループの中で配列やリストを増やし続けていないか確かめましょう。";
        }
        if (stderr.contains("ClassCastException")) {
            return "別の型に無理に変換しようとしています。";
        }
        if (stderr.contains("NegativeArraySizeException")) {
            return "配列の長さにマイナスの数を指定しています。";
        }
        return "";
    }

    // ------------------------------------------------------------- 細部の抽出

    /** メッセージ中で最初に引用符で囲まれたトークン。 */
    private static String firstQuoted(String message) {
        Matcher m = QUOTED.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 「シンボルを見つけられません」系のメッセージから識別子名を取り出す。
     *
     * javac は次の形で複数行を返す（英語環境でも構造は同じ）。
     * <pre>
     *   シンボルを見つけられません
     *     シンボル:   変数 nam         ← ここの最後の語が欲しい
     *     場所: クラス Main
     * </pre>
     */
    private static String symbolName(String message) {
        String[] lines = message.split("\n");
        if (lines.length < 2) {
            return null;
        }
        String[] words = lines[1].strip().split("\\s+");
        if (words.length == 0) {
            return null;
        }
        String last = words[words.length - 1];
        int paren = last.indexOf('(');   // メソッドは printline(java.lang.String) の形で出る
        if (paren > 0) {
            last = last.substring(0, paren);
        }
        return last.isEmpty() ? null : last;
    }

    /**
     * 見つからなかった名前が「import すれば使えるJDKのクラス」なら、そのパッケージ名。
     * 違えば null（自作クラスの書き忘れや、変数名のつづり間違いはこちらへ落ちる）。
     *
     * <p>javacのメッセージに書かれた種別（「クラス」「変数」）では判定しない。
     * {@code Files.writeString(...)} の import 忘れは、javacからは
     * <b>「シンボル: 変数 Files」</b>として届く（フィールド参照に見えるため）。
     * 大文字始まりであることと、そのパッケージに実物があることだけで決める。
     */
    private static String importPackageOf(String name) {
        if (name == null || !CLASS_NAME.matcher(name).matches()) {
            return null;
        }
        ClassLoader loader = ErrorTranslator.class.getClassLoader();
        for (String pkg : IMPORTABLE_PACKAGES) {
            try {
                Class.forName(pkg + "." + name, false, loader);
                return pkg;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // このパッケージには無い。次を試す
            }
        }
        return null;
    }
}
