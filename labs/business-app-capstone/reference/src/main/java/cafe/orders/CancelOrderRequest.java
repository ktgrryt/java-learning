package cafe.orders;

public record CancelOrderRequest(
        long actorId,
        String reason,
        String idempotencyKey,
        String requestId) {

    public String normalizedReason() {
        return reason == null ? "" : reason.strip();
    }

    public void validate() {
        String normalized = normalizedReason();
        if (normalized.isBlank() || normalized.length() > 100) {
            throw new InvalidRequestException("reason must contain 1 to 100 characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("idempotency key is required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new InvalidRequestException("request id is required");
        }
    }
}
