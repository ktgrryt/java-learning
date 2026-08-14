import java.util.HashMap;
import java.util.Map;

/**
 * 直近の注文だけを手元へ置くキャッシュ。
 *
 * put と get は正しく動く。ただし古い注文の参照が残り続けるので、GCしても回収できない。
 * 「解放を忘れた」のではなく「不要な参照を持ち続けている」形のメモリリークである。
 *
 * TODO: 直近 CAPACITY 件だけを保持する形へ直す。
 *       追い出したエントリへの参照は、どこにも残さない（残っているとGCが回収できない）。
 */
public final class RecentOrders {
    /** 手元へ置いておく件数。 */
    public static final int CAPACITY = 1000;

    private final Map<Long, byte[]> orders = new HashMap<>();

    public void put(long id, byte[] payload) {
        orders.put(id, payload);
    }

    public byte[] get(long id) {
        return orders.get(id);
    }

    public int size() {
        return orders.size();
    }
}
