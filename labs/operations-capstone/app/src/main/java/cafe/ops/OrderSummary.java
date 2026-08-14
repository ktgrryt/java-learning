package cafe.ops;

/**
 * 一覧APIが返す1件。
 *
 * <p>項目名と並び順はAPI契約（{@code api/openapi-v1.json}）で約束している。
 * 契約は「新しい注文から順に返す」と書いてあり、利用側の画面はその順で表示する。
 * 並びが変わってもコンパイルは通るので、契約違反はテストでしか気づけない。
 */
public record OrderSummary(long orderId, String storeName, int amount) {
}
