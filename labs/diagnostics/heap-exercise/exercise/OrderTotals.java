import java.util.List;

/**
 * 注文金額の合計。
 *
 * 再帰で書いてある。件数が少ないうちは動くが、1件ごとにスタックへフレームが積まれるので、
 * 大きな入力ではStackOverflowErrorになる。ヒープと違い、スタックはスレッドごとに小さい。
 *
 * TODO: 深い入力でも落ちない形へ直す。合計の意味は変えない。
 */
public final class OrderTotals {
    private OrderTotals() {
    }

    public static long sum(List<Long> amounts) {
        return sumFrom(amounts, 0);
    }

    private static long sumFrom(List<Long> amounts, int index) {
        if (index == amounts.size()) {
            return 0L;
        }
        return amounts.get(index) + sumFrom(amounts, index + 1);
    }
}
