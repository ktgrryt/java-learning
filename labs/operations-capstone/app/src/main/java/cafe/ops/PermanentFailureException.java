package cafe.ops;

/** 何度試しても直らない失敗。再試行しても下流を無駄に叩くだけになる。 */
public final class PermanentFailureException extends RuntimeException {

    private final String code;

    public PermanentFailureException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
