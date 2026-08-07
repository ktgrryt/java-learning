package jq.judge;

import jq.content.TestCase;
import jq.runner.ErrorTranslator;
import jq.runner.RunResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 実行結果と期待出力を突き合わせて合否を決める。
 *
 * 判定は「見た目が同じなら合格」を狙う。行末の空白と末尾の空行は初心者が
 * 気づきにくく、学習の本題でもないので無視する。それ以外は完全一致を求める。
 */
public final class Judge {

    private Judge() {
    }

    public static CaseResult judge(TestCase testCase, RunResult run) {
        String expected = normalize(testCase.expected());
        String actual = normalize(run.stdout());

        boolean pass = !run.timedOut() && !run.crashed() && expected.equals(actual);
        List<CaseResult.DiffLine> diff = pass ? List.of() : diff(expected, actual);

        String hint;
        if (run.timedOut()) {
            hint = "5秒以内に終わりませんでした。ループが終わらなくなっていないか、"
                    + "条件やカウンタの増やし忘れを確かめましょう。";
        } else {
            hint = ErrorTranslator.forRuntimeError(run.stderr());
        }

        return new CaseResult(
                testCase.label(),
                testCase.hidden(),
                pass,
                testCase.stdin(),
                expected,
                actual,
                diff,
                run.stderr(),
                hint,
                run.timedOut());
    }

    /** 行末の空白を除去し、CRLF を LF にそろえ、末尾の空行を落とす。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> stripped = new ArrayList<>(lines.length);
        for (String line : lines) {
            stripped.add(stripTrailing(line));
        }
        int end = stripped.size();
        while (end > 0 && stripped.get(end - 1).isEmpty()) {
            end--;
        }
        return String.join("\n", stripped.subList(0, end));
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /** 期待と実際を行単位で並べる。行数が違う場合は足りない側を null にする。 */
    static List<CaseResult.DiffLine> diff(String expected, String actual) {
        String[] e = expected.isEmpty() ? new String[0] : expected.split("\n", -1);
        String[] a = actual.isEmpty() ? new String[0] : actual.split("\n", -1);
        int max = Math.max(e.length, a.length);
        List<CaseResult.DiffLine> lines = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            String ev = i < e.length ? e[i] : null;
            String av = i < a.length ? a[i] : null;
            lines.add(new CaseResult.DiffLine(i + 1, ev, av, ev != null && ev.equals(av)));
        }
        return List.copyOf(lines);
    }
}
