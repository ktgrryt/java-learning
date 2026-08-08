package jakarta.servlet.http;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;

/**
 * 1回のHTTPリクエスト。
 *
 * 本物と同じく **インターフェース** で、実装はコンテナ（この教材では疑似コンテナ）が用意する。
 * だから学習者が {@code new} することはない。受け取って読むだけ。
 */
public interface HttpServletRequest {

    // ── リクエストの基本情報 ────────────────────────────────────────

    /** HTTPメソッド（{@code "GET"} / {@code "POST"} など）。 */
    String getMethod();

    /** クエリ文字列を除いたパス（例 {@code "/hello"}）。 */
    String getRequestURI();

    /** {@code ?} より後ろ。無ければ null。 */
    String getQueryString();

    // ── パラメータ（クエリ文字列やフォームの値） ────────────────────

    /**
     * パラメータを1つ取る。**無ければ null**（空文字ではない）。
     * 同じ名前が複数あるときは最初の1つ。
     */
    String getParameter(String name);

    /** 同じ名前のパラメータを全部取る。無ければ null。 */
    String[] getParameterValues(String name);

    // ── リクエストスコープの属性 ────────────────────────────────────

    /** リクエストスコープに値を入れる。次のリクエストには残らない。 */
    void setAttribute(String name, Object value);

    /** リクエストスコープの値。無ければ null。 */
    Object getAttribute(String name);

    void removeAttribute(String name);

    // ── セッションとCookie ──────────────────────────────────────────

    /** セッションを取る。無ければ新しく作る（本物と同じ）。 */
    HttpSession getSession();

    /**
     * @param create false なら、無いときに作らず null を返す
     */
    HttpSession getSession(boolean create);

    /** 送られてきたCookie。1つも無ければ null（本物と同じで、空配列ではない）。 */
    Cookie[] getCookies();

    // ── 転送とアプリ全体 ────────────────────────────────────────────

    /** 別のサーブレットやビューへ転送するための道具。 */
    RequestDispatcher getRequestDispatcher(String path);

    /** アプリ全体で共有される入れ物（アプリケーションスコープ）。 */
    ServletContext getServletContext();

    /** 文字コードの指定。本物ではPOSTの文字化け対策で必要になる。 */
    void setCharacterEncoding(String encoding);
}
