package jakarta.servlet;

/**
 * サーブレットの処理中に起きた例外。
 *
 * 本物と同じ検査例外なので、{@code doGet} / {@code doPost} で投げるなら
 * {@code throws ServletException} を書く必要がある。
 */
public class ServletException extends Exception {

    private static final long serialVersionUID = 1L;

    public ServletException() {
        super();
    }

    public ServletException(String message) {
        super(message);
    }

    public ServletException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServletException(Throwable cause) {
        super(cause);
    }
}
