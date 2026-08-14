package cafe.ops;

import java.util.List;

/**
 * 一覧に必要なデータの取り出し口。
 *
 * <p>{@link #findOrders(long)} は注文だけを返し、店舗名は持たない。
 * 店舗名を1件ずつ引くと注文の件数だけ問い合わせが増える。
 * {@link #findStoreNames(java.util.Collection)} は必要な店舗をまとめて引く。
 */
public interface OrderQueryPort {

    List<OrderRow> findOrders(long customerId);

    /** 渡したIDの店舗名をまとめて返す。1回の問い合わせで済む。 */
    java.util.Map<Long, String> findStoreNames(java.util.Collection<Long> storeIds);

    /** 店舗名を1件だけ引く。件数分呼ぶと問い合わせが増える。 */
    String findStoreName(long storeId);
}
