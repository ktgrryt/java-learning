package jakarta.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * サーブレットの前後に割り込む部品。
 *
 * 認証チェック・文字コード設定・アクセスログのように「どのサーブレットでも同じようにやること」を
 * 1か所へ集めるために使う。
 *
 * <pre>
 * &#64;WebFilter("/*")
 * public class LogFilter implements Filter {
 *     &#64;Override
 *     public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
 *             throws ServletException, IOException {
 *         // ここは前処理
 *         chain.doFilter(req, res);   // ← 次（別のフィルタ、最後はサーブレット）へ渡す
 *         // ここは後処理
 *     }
 * }
 * </pre>
 *
 * <p><b>要注意</b>：{@code chain.doFilter(...)} を呼び忘れると、そこで止まって
 * サーブレットまで届かない。「認証で弾く」ときは意図的に呼ばない。
 *
 * <p>本物の {@code doFilter} は {@code ServletRequest} / {@code ServletResponse} を受け取り、
 * HTTP用に使うにはキャストが必要。この教材では最初からHTTP版を受け取る形に省いている。
 */
public interface Filter {

    void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException;

    /** 最初のリクエストの前に1回だけ呼ばれる。 */
    default void init() throws ServletException {
        // 既定では何もしない
    }

    /** アプリが止まるときに1回だけ呼ばれる。 */
    default void destroy() {
        // 既定では何もしない
    }
}
