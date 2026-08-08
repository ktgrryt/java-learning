package jakarta.servlet.http;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.io.IOException;

/**
 * サーブレットの親クラス。これを継承して {@code doGet} / {@code doPost} を上書きする。
 *
 * <pre>
 * &#64;WebServlet("/hello")
 * public class HelloServlet extends HttpServlet {
 *     &#64;Override
 *     protected void doGet(HttpServletRequest req, HttpServletResponse res)
 *             throws ServletException, IOException {
 *         res.getWriter().println("こんにちは");
 *     }
 * }
 * </pre>
 *
 * <p><b>大事な性質</b>：コンテナはこのクラスの<b>インスタンスを1つだけ</b>作って、
 * すべてのリクエストで使い回す。だからフィールドに状態を持たせると、
 * 全員で共有されてしまう（第22章のスレッドセーフの話）。
 */
public abstract class HttpServlet {

    private ServletContext servletContext;

    /**
     * コンテナが呼ぶ入口。HTTPメソッドを見て {@code doGet} / {@code doPost} へ振り分ける。
     *
     * <p>本物では入口が {@code service(ServletRequest, ServletResponse)}（public）で、
     * そこから protected な {@code service(HttpServletRequest, HttpServletResponse)} を
     * 経由して doGet/doPost に届く。この教材では段数を1つに省いている。
     */
    public void service(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        switch (req.getMethod()) {
            case "GET" -> doGet(req, res);
            case "POST" -> doPost(req, res);
            case "PUT" -> doPut(req, res);
            case "DELETE" -> doDelete(req, res);
            default -> res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }

    /**
     * GET を処理する。上書きしなければ 405（Method Not Allowed）を返す。
     *
     * 上書きするときは protected のままでよい（コンテナは {@code service} 経由で呼ぶ）。
     */
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    protected void doPut(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /**
     * インスタンスが作られたあと、最初のリクエストの前に<b>1回だけ</b>呼ばれる。
     * DBのコネクションプールの用意など、重い準備をここでやる。
     */
    public void init() throws ServletException {
        // 既定では何もしない
    }

    /** アプリが止まるときに1回だけ呼ばれる。後片付けをここでやる。 */
    public void destroy() {
        // 既定では何もしない
    }

    /** アプリ全体で共有される入れ物。 */
    public ServletContext getServletContext() {
        return servletContext;
    }

    /** 疑似コンテナが使う。学習者が呼ぶことはない。 */
    public final void setServletContextForContainer(ServletContext context) {
        this.servletContext = context;
    }
}
