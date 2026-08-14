package cafe.ops;

import java.util.HashSet;
import java.util.Set;

public final class InventoryReleaseService {

    public static final int MAX_ATTEMPTS = 3;
    public static final long BASE_WAIT_MILLIS = 200L;

    private final InventoryClient client;
    private final Sleeper sleeper;
    /** 成功した冪等キー。再送で下流をもう一度叩かないために覚えておく。 */
    private final Set<String> completedKeys = new HashSet<>();

    public InventoryReleaseService(InventoryClient client, Sleeper sleeper) {
        this.client = client;
        this.sleeper = sleeper;
    }

    public ReleaseResult release(long orderId, String idempotencyKey) {
        if (completedKeys.contains(idempotencyKey)) {
            // 一度成功した要求。下流はもう処理しているので送り直さない。
            return ReleaseResult.success(0);
        }

        String transientCode = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                client.release(orderId, idempotencyKey);
                completedKeys.add(idempotencyKey);
                return ReleaseResult.success(attempt);
            } catch (TransientFailureException e) {
                // code() は監視で数えるための短い名前。getMessage() には下流の内部情報が入る。
                transientCode = e.code();
                if (attempt < MAX_ATTEMPTS) {
                    // 間隔を空けずに叩き続けると、復旧しかけている下流をまた倒す。
                    sleeper.sleep(BASE_WAIT_MILLIS << (attempt - 1));
                }
            } catch (PermanentFailureException e) {
                // 何度試しても同じ結果になる。下流を無駄に叩かない。
                return ReleaseResult.failure(e.code(), attempt);
            }
        }
        return ReleaseResult.failure(transientCode, MAX_ATTEMPTS);
    }
}
