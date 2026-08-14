package cafe.api;

/**
 * 注文（サーバー内部の形）。参照専用（この演習では編集しません）。
 *
 * <p>{@code internalNote}は社内メモです。<b>外へ出してはいけません</b>。
 * 内部の形をそのままJSONへ返すと、こういう項目が一緒に出ていきます。
 */
public record Order(String id, String item, int quantity, String customer, String internalNote) {
}
