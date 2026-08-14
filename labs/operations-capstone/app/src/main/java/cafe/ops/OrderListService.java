package cafe.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * 一覧APIの中身。
 *
 * <p>配備 orders-2.4.0 で持ち込まれた不具合が2つある。
 *
 * <ol>
 *   <li>店舗名を注文1件ごとに引いている。注文が100件なら問い合わせは101回になる（遅い原因）。</li>
 *   <li>API契約が約束している並び順（新しい注文から）を守っていない（利用側の画面が狂う原因）。</li>
 * </ol>
 */
public final class OrderListService {

    private final OrderQueryPort port;

    public OrderListService(OrderQueryPort port) {
        this.port = port;
    }

    public List<OrderSummary> list(long customerId) {
        List<OrderRow> rows = port.findOrders(customerId);
        List<OrderSummary> summaries = new ArrayList<>();
        for (OrderRow row : rows) {
            // TODO: 1件ずつ引くのをやめ、必要な店舗名を findStoreNames でまとめて1回引く。
            //       注文が0件のときは店舗の問い合わせを投げない。
            String storeName = port.findStoreName(row.storeId());
            summaries.add(new OrderSummary(row.orderId(), storeName, row.amount()));
        }
        // TODO: API契約どおり、新しい注文（orderIdの大きいもの）から順に返す
        return summaries;
    }
}
