package cafe.api;

import java.util.concurrent.atomic.AtomicInteger;

/** 注文番号の採番。参照専用（この演習では編集しません）。 */
public final class OrderStore {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(5000);

    private OrderStore() {
    }

    /** 次の注文番号を返す。 */
    public static String nextId() {
        return "ORD-" + NEXT_ID.incrementAndGet();
    }
}
