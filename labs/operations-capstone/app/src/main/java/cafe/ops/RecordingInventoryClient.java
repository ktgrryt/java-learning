package cafe.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * 試験用の在庫サービス。指定した回数だけ一時障害を返し、その後は成功する。
 *
 * <p>失敗の詳細には、下流が返してくる内部情報をそのまま入れてある
 * （ホスト名や資格情報など）。これを応答へ透かしてしまわないかを試すため。
 */
public final class RecordingInventoryClient implements InventoryClient {

    /** 下流がそのまま返してくる内部情報。応答へ出してはいけない。 */
    public static final String INTERNAL_DETAIL =
            "inventory-db-07.internal:5432 token=Bearer-prod-secret";

    private final int transientFailures;
    private final boolean permanent;
    private final List<String> calls = new ArrayList<>();

    public RecordingInventoryClient(int transientFailures, boolean permanent) {
        this.transientFailures = transientFailures;
        this.permanent = permanent;
    }

    @Override
    public void release(long orderId, String idempotencyKey) {
        calls.add(orderId + ":" + idempotencyKey);
        if (permanent) {
            throw new PermanentFailureException("order_not_found", INTERNAL_DETAIL);
        }
        if (calls.size() <= transientFailures) {
            throw new TransientFailureException("inventory_timeout", INTERNAL_DETAIL);
        }
    }

    /** 下流を実際に呼んだ回数。冪等キーで抑えられていれば増えない。 */
    public int callCount() {
        return calls.size();
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
