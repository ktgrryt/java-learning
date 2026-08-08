package jakarta.servlet.http;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * 1回のHTTPレスポンス。
 *
 * 本物と同じくインターフェースで、実装はコンテナが用意する。
 * 本文は {@link #getWriter()} に書き、状態は {@link #setStatus(int)} で決める。
 */
public interface HttpServletResponse {

    // ── よく使うステータスコード（本物にも同じ定数がある） ──────────

    int SC_OK = 200;
    int SC_CREATED = 201;
    int SC_NO_CONTENT = 204;
    int SC_MOVED_PERMANENTLY = 301;
    int SC_FOUND = 302;
    int SC_BAD_REQUEST = 400;
    int SC_UNAUTHORIZED = 401;
    int SC_FORBIDDEN = 403;
    int SC_NOT_FOUND = 404;
    int SC_METHOD_NOT_ALLOWED = 405;
    int SC_CONFLICT = 409;
    int SC_INTERNAL_SERVER_ERROR = 500;

    // ── 本文 ────────────────────────────────────────────────────────

    /** 本文を書くための出力先。 */
    PrintWriter getWriter() throws IOException;

    /** {@code "text/html; charset=UTF-8"} など。 */
    void setContentType(String type);

    // ── ステータスとヘッダ ──────────────────────────────────────────

    void setStatus(int sc);

    int getStatus();

    void setHeader(String name, String value);

    String getHeader(String name);

    /** エラーとして返す。本文はここで決まるので、以降 {@code getWriter()} には書かない。 */
    void sendError(int sc) throws IOException;

    void sendError(int sc, String message) throws IOException;

    /** 別のURLへ行き直させる（302）。ブラウザが改めてリクエストを送る。 */
    void sendRedirect(String location) throws IOException;

    // ── Cookie ──────────────────────────────────────────────────────

    void addCookie(Cookie cookie);
}
