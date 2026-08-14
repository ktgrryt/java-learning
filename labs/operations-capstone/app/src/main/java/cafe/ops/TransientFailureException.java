package cafe.ops;

/** 時間をおけば直る失敗。再試行してよい。 */
public final class TransientFailureException extends RuntimeException {

    private final String code;

    public TransientFailureException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    /** 監視で数えるための短い名前。応答へ出してよい。 */
    public String code() {
        return code;
    }
}
