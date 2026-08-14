package cafe.ops;

/** 在庫サービス（下流）への呼び出し口。 */
public interface InventoryClient {

    /**
     * 注文ぶんの在庫を解放する。
     *
     * @param idempotencyKey 同じ要求を二重に処理させないための鍵。下流もこれを見る。
     * @throws TransientFailureException 時間をおけば直る失敗（timeout、502など）
     * @throws PermanentFailureException 何度試しても直らない失敗（存在しない注文など）
     */
    void release(long orderId, String idempotencyKey);
}
