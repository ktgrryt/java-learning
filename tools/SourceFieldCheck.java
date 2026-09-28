import jq.content.SourceCheck;
import jq.judge.SourceChecker;
import java.util.List;

/** Servlet課題のフィールド検査が識別子や修飾子に依存しないことを確認する。 */
public class SourceFieldCheck {
    public static void main(String[] args) {
        var check = SourceCheck.of("SafeServlet", 0, 0, "no fields", "fields");
        for (String field : List.of("private String lastName;", "String renamed;",
                "static String name;", "final String[] holder = new String[1];",
                "@Deprecated protected volatile Object state;", "int a, b;")) {
            String source = "class SafeServlet { void doGet() {} " + field + " }";
            if (SourceChecker.failures(List.of(check), source).isEmpty()) {
                throw new AssertionError("フィールドを見逃した: " + field);
            }
        }
        String locals = "class SafeServlet { void doGet(String name) { String local = name; "
                + "String text = \"private String fake;\"; } /* int fake; */ }";
        if (!SourceChecker.failures(List.of(check), locals).isEmpty()) {
            throw new AssertionError("ローカル変数・引数・コメント・リテラルをフィールドと数えた");
        }
        var regex = SourceCheck.of("\\bdoGet\\s*\\(", 1, 1, "method");
        if (!SourceChecker.failures(List.of(regex), locals).isEmpty()) {
            throw new AssertionError("既存のregex検査を壊した");
        }
        System.out.println("Servlet構文検査: 別名・修飾子・配列・ローカル変数の8ケース合格");
    }
}
