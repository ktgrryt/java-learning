package cafe.web;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注文の置き場。参照専用（この演習では編集しません）。
 *
 * <p>ここは<b>すでにスレッドセーフ</b>です。複数のスレッドから同時に呼ばれても壊れません。
 * それでもServletの書き方によっては要求ごとのデータが混ざります。混ざる原因は
 * 置き場ではなく、{@code OrderServlet}が値をどこに置くかにあります。
 */
public final class OrderStore {

    private final Map<String, String> items = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1000);

    /** 注文を1件足して、採番したIDを返す。 */
    public String add(String item) {
        String id = String.valueOf(nextId.incrementAndGet());
        items.put(id, item);
        return id;
    }

    /** IDで注文の品名を探す。無ければ空。 */
    public Optional<String> find(String id) {
        return Optional.ofNullable(items.get(id));
    }
}
