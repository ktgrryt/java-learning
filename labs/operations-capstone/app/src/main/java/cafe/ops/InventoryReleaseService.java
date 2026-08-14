package cafe.ops;

/**
 * 注文キャンセル時に、在庫サービスへ解放を依頼する。
 *
 * <p>いま起きていること: 一時障害でも1回で諦めている。在庫サービスは
 * {@code inventory_timeout} を短時間だけ返すことがあり、そのたびにキャンセルが失敗している。
 *
 * <p>直すときの約束。
 *
 * <ul>
 *   <li>一時障害（{@link TransientFailureException}）は<b>最大{@link #MAX_ATTEMPTS}回</b>まで試す。</li>
 *   <li>試行のあいだは待つ。待ち時間は{@link #BASE_WAIT_MILLIS}から始めて<b>毎回2倍</b>にする
 *       （間隔を空けずに叩き続けると、復旧しかけている下流をまた倒す）。</li>
 *   <li>恒久障害（{@link PermanentFailureException}）は再試行しない。何度でも同じ結果になる。</li>
 *   <li>打ち切ったときの{@code errorCode}は短い名前だけにする。下流が返す内部情報
 *       （ホスト名や資格情報）を応答へ透かさない。</li>
 *   <li>同じ冪等キーで一度成功していたら、下流へ送り直さない。</li>
 * </ul>
 */
public final class InventoryReleaseService {

    public static final int MAX_ATTEMPTS = 3;
    public static final long BASE_WAIT_MILLIS = 200L;

    private final InventoryClient client;
    private final Sleeper sleeper;

    public InventoryReleaseService(InventoryClient client, Sleeper sleeper) {
        this.client = client;
        this.sleeper = sleeper;
    }

    public ReleaseResult release(long orderId, String idempotencyKey) {
        // TODO: 同じ冪等キーで成功済みなら、下流を呼ばずに成功を返す
        try {
            client.release(orderId, idempotencyKey);
            return ReleaseResult.success(1);
        } catch (TransientFailureException e) {
            // TODO: MAX_ATTEMPTS回まで試す。待ち時間はBASE_WAIT_MILLISから毎回2倍にする
            // TODO: 打ち切ったら e.code() だけを errorCode にする（e.getMessage()は入れない）
            return ReleaseResult.failure(e.getMessage(), 1);
        } catch (PermanentFailureException e) {
            // TODO: 再試行せずに終える。errorCode は e.code() にする
            return ReleaseResult.failure(e.getMessage(), 1);
        }
    }
}
