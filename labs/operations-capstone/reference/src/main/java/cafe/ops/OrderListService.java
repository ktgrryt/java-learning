package cafe.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OrderListService {

    private final OrderQueryPort port;

    public OrderListService(OrderQueryPort port) {
        this.port = port;
    }

    public List<OrderSummary> list(long customerId) {
        List<OrderRow> rows = port.findOrders(customerId);
        if (rows.isEmpty()) {
            // 空の集合で問い合わせても得るものは無い。無駄な往復を作らない。
            return List.of();
        }

        Set<Long> storeIds = new LinkedHashSet<>();
        for (OrderRow row : rows) {
            storeIds.add(row.storeId());
        }
        // 注文の件数に関係なく1回。同じ店舗が何度出てきてもまとめて1回で済む。
        Map<Long, String> storeNames = port.findStoreNames(storeIds);

        List<OrderSummary> summaries = new ArrayList<>();
        for (OrderRow row : rows) {
            summaries.add(new OrderSummary(
                    row.orderId(), storeNames.get(row.storeId()), row.amount()));
        }
        // API契約が約束している並び。利用側の画面はこの順で表示する。
        summaries.sort(Comparator.comparingLong(OrderSummary::orderId).reversed());
        return summaries;
    }
}
