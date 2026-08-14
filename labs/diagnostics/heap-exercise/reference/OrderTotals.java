import java.util.List;

/**
 * 注文金額の合計。
 *
 * 繰り返しで書けば、件数が増えてもスタックのフレームは1つで済む。
 */
public final class OrderTotals {
    private OrderTotals() {
    }

    public static long sum(List<Long> amounts) {
        long total = 0L;
        for (long amount : amounts) {
            total += amount;
        }
        return total;
    }
}
