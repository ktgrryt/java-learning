package cafe.orders;

public record CancelOrderRequest(
        long actorId,
        String reason,
        String idempotencyKey,
        String requestId) {

    public String normalizedReason() {
        // TODO: nullを空文字として扱い、前後の空白を除く
        return reason;
    }

    public void validate() {
        // TODO: 理由は1〜100文字、冪等キーとrequestIdは空不可
    }
}
