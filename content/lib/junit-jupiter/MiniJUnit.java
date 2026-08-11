import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * このアプリの中でテストを走らせる、小さなテストランナー。
 *
 * <p>本物のJUnitは Maven や Gradle がテストクラスを探して起動する。ここではその係を
 * このクラスが受け持ち、{@code main} から明示的に呼ぶ。書くテストの側
 * （{@code @Test} と {@code assertEquals}）は本物とまったく同じで、
 * 起動のしかただけが違う。実物は {@code labs/testing-maven} で {@code mvn test} で動かせる。
 *
 * <h2>実行の順番について</h2>
 * ここでは <b>メソッド名の順</b> に実行する。{@link Class#getDeclaredMethods()} が返す順番は
 * 仕様として決まっておらず、JVMの実装によって変わりうるので、そのまま使うと同じコードでも
 * 出力が変わってしまう。
 *
 * <p>本物のJUnitの既定順序は再現可能だが、意図的に分かりにくいアルゴリズムで決まる。
 * 順序を推測して「前のテストが残した状態」に依存させず、1件ずつ独立に書く。
 * それを守るために、JUnitはテストごとにインスタンスを作り直す。ここも同じにしてある。
 */
public final class MiniJUnit {

    private MiniJUnit() {
    }

    /** テスト1件の結果。 */
    private enum Outcome {
        /** 通った。 */
        PASS,
        /** 検証が合わなかった（{@code AssertionError}）。 */
        FAILURE,
        /** 検証以外の例外で落ちた。テスト自体か対象コードの不具合。 */
        ERROR
    }

    /**
     * テストクラスを走らせ、結果を標準出力へ書く。
     *
     * <p>出力は Maven の surefire に似せた形にしてある。
     * <pre>{@code
     * Running PriceRulesTest
     *   PASS rate0KeepsPrice
     *   FAIL rate100IsFree -- expected: <0> but was: <12>
     * Tests run: 2, Failures: 1, Errors: 0
     * }</pre>
     *
     * @param testClasses {@code @Test} を付けたメソッドを持つクラス
     */
    public static void run(Class<?>... testClasses) {
        for (Class<?> testClass : testClasses) {
            System.out.println("Running " + testClass.getSimpleName());
            int total = 0;
            int failures = 0;
            int errors = 0;

            for (Method method : testMethods(testClass)) {
                for (Object[] arguments : argumentSets(method)) {
                    total++;
                    Outcome outcome = runOne(testClass, method, arguments, label(method, arguments));
                    if (outcome == Outcome.FAILURE) {
                        failures++;
                    } else if (outcome == Outcome.ERROR) {
                        errors++;
                    }
                }
            }

            System.out.println("Tests run: " + total + ", Failures: " + failures
                    + ", Errors: " + errors);
        }
    }

    /** テストメソッドを名前順に集める。順番を決め打ちにするのは出力を安定させるため。 */
    private static List<Method> testMethods(Class<?> testClass) {
        List<Method> found = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class)
                    || method.isAnnotationPresent(ParameterizedTest.class)) {
                found.add(method);
            }
        }
        found.sort(Comparator.comparing(Method::getName)
                .thenComparingInt(Method::getParameterCount));
        return found;
    }

    /**
     * 1件ぶんの引数の組を並べて返す。
     *
     * <p>ふつうの {@code @Test} は引数なしなので1組だけ。{@code @ParameterizedTest} は
     * {@code @CsvSource} の行数ぶん返るので、1メソッドが何回も走る。
     */
    private static List<Object[]> argumentSets(Method method) {
        CsvSource csv = method.getAnnotation(CsvSource.class);
        if (csv == null) {
            // 型引数を明示しないと、配列そのものが可変長引数として展開されてしまう。
            return List.<Object[]>of(new Object[0]);
        }
        List<Object[]> sets = new ArrayList<>();
        for (String row : csv.value()) {
            String[] cells = row.split(",", -1);
            Class<?>[] types = method.getParameterTypes();
            Object[] arguments = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                arguments[i] = convert(i < cells.length ? cells[i].trim() : "", types[i]);
            }
            sets.add(arguments);
        }
        return sets;
    }

    /** CSVの1マスを、受け取る側の型へ合わせる。 */
    private static Object convert(String cell, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(cell);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(cell);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(cell);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(cell);
        }
        return cell;
    }

    /** レポートに出す名前。パラメータ付きなら何番目かを添える。 */
    private static String label(Method method, Object[] arguments) {
        DisplayName displayName = method.getAnnotation(DisplayName.class);
        String base = displayName != null ? displayName.value() : method.getName();
        if (arguments.length == 0) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append('[');
        for (int i = 0; i < arguments.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(arguments[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * テストを1件走らせて、結果を1行書く。
     *
     * <p>インスタンスはここで作る。テストごとに作り直すので、フィールドに書いた値が
     * 次のテストへ漏れない。
     */
    private static Outcome runOne(Class<?> testClass, Method method, Object[] arguments,
                                 String label) {
        try {
            Object instance = newInstance(testClass);
            for (Method before : beforeEachMethods(testClass)) {
                before.setAccessible(true);
                before.invoke(instance);
            }
            method.setAccessible(true);
            method.invoke(instance, arguments);
        } catch (InvocationTargetException wrapped) {
            // リフレクション経由の呼び出しは、中で起きた例外をこの型で包んで返す。
            // 学習者が見たいのは中身なので、必ず getCause() を取り出す。
            Throwable cause = wrapped.getCause();
            if (cause instanceof AssertionError) {
                System.out.println("  FAIL " + label + " -- " + cause.getMessage());
                return Outcome.FAILURE;
            }
            System.out.println("  ERROR " + label + " -- " + describe(cause));
            return Outcome.ERROR;
        } catch (ReflectiveOperationException e) {
            System.out.println("  ERROR " + label + " -- テストを起動できません: " + describe(e));
            return Outcome.ERROR;
        }
        System.out.println("  PASS " + label);
        return Outcome.PASS;
    }

    /** 引数なしのコンストラクタでテストクラスを作る。 */
    private static Object newInstance(Class<?> testClass) throws ReflectiveOperationException {
        Constructor<?> constructor = testClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** 準備メソッドも名前順にそろえる。 */
    private static List<Method> beforeEachMethods(Class<?> testClass) {
        List<Method> found = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(BeforeEach.class)) {
                found.add(method);
            }
        }
        found.sort(Comparator.comparing(Method::getName));
        return found;
    }

    /** 例外を「型: メッセージ」の形にする。メッセージなしなら型だけ。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "原因不明";
        }
        String name = t.getClass().getName();
        return t.getMessage() == null ? name : name + ": " + t.getMessage();
    }
}
