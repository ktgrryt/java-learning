package cafe.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 問い合わせ回数を数える試験用の実装。
 *
 * <p>速さを秒で測ると、走らせた機械やその時の負荷で結果が変わる。ここでは
 * <b>問い合わせを何回投げたか</b>を数える。1件ずつ引く実装と、まとめて引く実装の差は
 * 回数にそのまま出るので、どの機械でも同じ結果になる。
 */
public final class CountingOrderQueryPort implements OrderQueryPort {

    private final List<OrderRow> rows;
    private final Map<Long, String> storeNames;
    private final List<String> queries = new ArrayList<>();

    public CountingOrderQueryPort(List<OrderRow> rows, Map<Long, String> storeNames) {
        this.rows = List.copyOf(rows);
        this.storeNames = Map.copyOf(storeNames);
    }

    @Override
    public List<OrderRow> findOrders(long customerId) {
        queries.add("orders");
        return rows;
    }

    @Override
    public Map<Long, String> findStoreNames(Collection<Long> storeIds) {
        queries.add("stores:" + storeIds.size());
        Map<Long, String> found = new LinkedHashMap<>();
        for (Long id : storeIds) {
            String name = storeNames.get(id);
            if (name != null) {
                found.put(id, name);
            }
        }
        return found;
    }

    @Override
    public String findStoreName(long storeId) {
        queries.add("store:" + storeId);
        return storeNames.get(storeId);
    }

    public int queryCount() {
        return queries.size();
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }
}
