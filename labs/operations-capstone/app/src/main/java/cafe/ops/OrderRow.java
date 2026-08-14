package cafe.ops;

/** 注文テーブルの1行。店舗名は別のテーブルにあるので、ここには無い。 */
public record OrderRow(long orderId, long storeId, int amount) {
}
