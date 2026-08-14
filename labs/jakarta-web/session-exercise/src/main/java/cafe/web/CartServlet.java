package cafe.web;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 買い物かごとログインを扱うServlet。
 *
 * <p>いまの実装は、かごを<b>Servletのフィールド</b>に持っています。1人で試すと動きますが、
 * サーバーには全員が同じインスタンスを通るので、他人のかごが見えます。
 * ログインとCookieの扱いにも問題があります。実サーバーへ当てて4つを別に測ります。
 *
 * <p>満たすこと:
 *
 * <ul>
 *   <li>{@code POST /cart?item=X} … このブラウザのかごへ追加し、
 *       {@code user=<名前> cart=[品名, 品名]} を返す</li>
 *   <li>{@code GET /cart} … このブラウザのかごを同じ形で返す</li>
 *   <li>{@code POST /cart/login?user=X} … ログインする。
 *       <b>セッションIDを作り直す</b>こと（ログイン前のIDが使い回されないように）。
 *       あわせて{@code visitor} Cookieを安全な属性つきで返す</li>
 *   <li>{@code POST /cart/logout} … セッションを無効にし、{@code user=anonymous cart=[]} を返す</li>
 * </ul>
 *
 * <p>クラス名・URLのマッピングは採点の足場が使うので変えないこと。
 */
@WebServlet("/cart/*")
public class CartServlet extends HttpServlet {

    /** かご。 */
    private final List<String> cart = new ArrayList<>();

    /** ログイン中の利用者。 */
    private String user = "anonymous";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // TODO: 利用者ごとの状態は、Servletのフィールドではなくセッションへ持つ
        write(response, user, cart);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getPathInfo() == null ? "/" : request.getPathInfo();

        if (path.startsWith("/login")) {
            user = orAnonymous(request.getParameter("user"));
            // TODO: ログインの前後でセッションIDを作り直す（使い回すと乗っ取りに使われる）
            // TODO: Cookieへ安全な属性を付ける
            Cookie visitor = new Cookie("visitor", user);
            visitor.setPath("/");
            response.addCookie(visitor);
            write(response, user, cart);
            return;
        }

        if (path.startsWith("/logout")) {
            // TODO: セッションを無効にする
            user = "anonymous";
            cart.clear();
            write(response, user, cart);
            return;
        }

        String item = request.getParameter("item");
        if (item != null && !item.isBlank()) {
            cart.add(item);
        }
        write(response, user, cart);
    }

    private static String orAnonymous(String name) {
        return name == null || name.isBlank() ? "anonymous" : name;
    }

    /** 応答の形は採点の足場が読むので変えないこと。 */
    private static void write(HttpServletResponse response, String user, List<String> cart)
            throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.getWriter().write("user=" + user + " cart=" + cart);
    }
}
