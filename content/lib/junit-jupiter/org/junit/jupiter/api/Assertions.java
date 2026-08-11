package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;

import java.util.Objects;

/**
 * 検証メソッドの置き場。本物のJUnit 5と同じクラス名・同じ引数の並び・同じ失敗文面にしてある。
 *
 * <p>使うときは {@code import static org.junit.jupiter.api.Assertions.*;} と書く。
 * static import しておけば {@code Assertions.assertEquals(...)} ではなく
 * {@code assertEquals(...)} と書けて、テスト本文が読みやすくなる。
 *
 * <p><b>引数の並びは「期待値が先、実測があと」</b>。逆に書いても等価判定の結果は同じだが、
 * 失敗したときの {@code expected} と {@code actual} が入れ替わって表示され、
 * 原因を読み違える。ここが初学者のいちばんの落とし穴。
 *
 * <p>本物のJUnitは失敗を {@code org.opentest4j.AssertionFailedError} で通知する。
 * これは {@link AssertionError} の一種なので、ここでは {@code AssertionError} を直接投げている。
 * テストランナーから見た捕まえ方は本物と同じになる。
 */
public final class Assertions {

    private Assertions() {
    }

    /** 期待値と実測値が等しいことを確かめる。{@code int} 同士はこの版が選ばれる。 */
    public static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(mismatch(expected, actual));
        }
    }

    /** 失敗したときに、何を確かめていたのかを説明する文を添える版。 */
    public static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " ==> " + mismatch(expected, actual));
        }
    }

    /** {@code long} 同士。{@code int} を渡しても long へ拡張されるので、こちらが選ばれることもある。 */
    public static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(mismatch(expected, actual));
        }
    }

    /**
     * 参照型どうし。{@code null} 同士は等しいと見なし、それ以外は {@code equals} で比べる。
     *
     * <p>だから比べる型に {@code equals} の実装が必要。実装していない自作クラスを渡すと
     * 同一インスタンスかどうかの判定になり、値が同じでも失敗する。
     */
    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(mismatch(expected, actual));
        }
    }

    /** 参照型どうしで、説明を添える版。 */
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " ==> " + mismatch(expected, actual));
        }
    }

    /** 条件が真であることを確かめる。 */
    public static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError(mismatch(true, false));
        }
    }

    /** 条件が真であることを確かめ、失敗したら説明を出す。 */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message + " ==> " + mismatch(true, false));
        }
    }

    /** 条件が偽であることを確かめる。 */
    public static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError(mismatch(false, true));
        }
    }

    /** {@code null} であることを確かめる。 */
    public static void assertNull(Object actual) {
        if (actual != null) {
            throw new AssertionError(mismatch(null, actual));
        }
    }

    /** {@code null} でないことを確かめる。 */
    public static void assertNotNull(Object actual) {
        if (actual == null) {
            throw new AssertionError("expected: not <null>");
        }
    }

    /**
     * 処理が指定した例外を投げることを確かめ、投げられた例外を返す。
     *
     * <p>返り値があるので、続けてメッセージや原因も検証できる。
     * <pre>{@code
     * var e = assertThrows(IllegalArgumentException.class, () -> discounted(-1, 10));
     * assertEquals("price", e.getMessage());
     * }</pre>
     *
     * <p>指定した型の <b>サブクラス</b> でも合格とする。本物のJUnitと同じ扱い。
     *
     * @param expectedType 投げられるはずの例外の型
     * @param executable   検証したい処理。ふつうはラムダで渡す
     */
    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            throw new AssertionError("Unexpected exception type thrown ==> expected: <"
                    + expectedType.getName() + "> but was: <" + actual.getClass().getName() + ">");
        }
        throw new AssertionError("Expected " + expectedType.getName()
                + " to be thrown, but nothing was thrown.");
    }

    /** ここまで来てはいけない場所で呼ぶ。必ず失敗する。 */
    public static void fail(String message) {
        throw new AssertionError(message);
    }

    /** 本物のJUnitとそろえた失敗文面を作る。 */
    private static String mismatch(Object expected, Object actual) {
        return "expected: <" + expected + "> but was: <" + actual + ">";
    }
}
