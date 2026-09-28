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
        List<String> failures = new ArrayList<>();
        for (SourceCheck check : checks) {
            int count = count(check, source);
            if (count < check.minimum()
                    || (check.maximum() >= 0 && count > check.maximum())) {
                failures.add(check.message());
            }
        }
        return List.copyOf(failures);
    }

    /** regexはソース、fieldsはクラス名に一致するクラスの直接のフィールドを数える。 */
    public static int count(SourceCheck check, String source) {
        if (check.type().equals("fields")) {
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("構文検査にはJDKが必要です");
            var input = new javax.tools.SimpleJavaFileObject(
                    java.net.URI.create("string:///Submission.java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return source; }
            };
            try (var manager = compiler.getStandardFileManager(null, null, null)) {
                var task = (com.sun.source.util.JavacTask) compiler.getTask(
                        new java.io.StringWriter(), manager, diagnostic -> {},
                        List.of("-proc:none"), null, List.of(input));
                int[] fields = {0};
                var scanner = new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitClass(com.sun.source.tree.ClassTree tree, Void unused) {
                        if (check.pattern().matcher(tree.getSimpleName()).matches()) {
                            for (var member : tree.getMembers()) {
                                if (member instanceof com.sun.source.tree.VariableTree) fields[0]++;
                            }
                        }
                        return super.visitClass(tree, unused);
                    }
                };
                for (var unit : task.parse()) scanner.scan(unit, null);
                return fields[0];
            } catch (java.io.IOException e) {
                throw new IllegalStateException("提出ソースの構文を確認できません", e);
            }
        }
        Matcher matcher = check.pattern().matcher(codeOnly(source));
        int count = 0;
        while (matcher.find()) count++;
        return count;
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
