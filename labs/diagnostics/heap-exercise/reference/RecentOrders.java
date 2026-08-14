import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直近の注文だけを手元へ置くキャッシュ。
 *
 * access-orderのLinkedHashMapに上限を付け、古いものは追い出す。追い出したエントリは
 * どこからも参照されなくなるので、GCが回収できる。
 */
public final class RecentOrders {
    /** 手元へ置いておく件数。 */
    public static final int CAPACITY = 1000;

    private final Map<Long, byte[]> orders =
            new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                    return size() > CAPACITY;
                }
            };

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
