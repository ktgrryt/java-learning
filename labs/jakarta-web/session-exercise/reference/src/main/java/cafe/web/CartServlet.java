package cafe.web;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 買い物かごとログインを扱うServlet（模範解答）。
 *
 * <p>要点は4つ。
 *
 * <ul>
 *   <li><b>利用者ごとの状態はセッションへ持つ。</b>Servletのフィールドは全員で共有されるので、
 *       他人のかごが見える。セッションはブラウザごとに別なので混ざらない。</li>
 *   <li><b>ログインでセッションIDを作り直す。</b>ログイン前のIDをそのまま使い続けると、
 *       攻撃者が先に取得させたIDでログイン後のセッションへ入れる（セッション固定攻撃）。
 *       {@code changeSessionId()}なら中身を保ったままIDだけ変えられる。</li>
 *   <li><b>Cookieには属性を付ける。</b>{@code HttpOnly}でJavaScriptから読めなくし、
 *       {@code SameSite}で他サイトからの送信を止める。HTTPSで動かすなら{@code Secure}も付ける
 *       （この演習はHTTPなので付けると届かなくなる）。</li>
 *   <li><b>ログアウトはセッションを無効にする。</b>値を消すだけでは、同じIDのセッションが
 *       残ったままになる。</li>
 * </ul>
 */
@WebServlet("/cart/*")
public class CartServlet extends HttpServlet {

    /** セッションへ入れるときの名前。 */
    private static final String CART = "cart";
    private static final String USER = "user";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpSession session = request.getSession();
        write(response, currentUser(session), currentCart(session));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        request.setCharacterEncoding("UTF-8");
        String path = request.getPathInfo() == null ? "/" : request.getPathInfo();

        if (path.startsWith("/login")) {
            HttpSession session = request.getSession();
            // ログイン前のIDを使い回さない。中身は保ったままIDだけ作り直す
            request.changeSessionId();
            String user = orAnonymous(request.getParameter(USER));
            session.setAttribute(USER, user);

            Cookie visitor = new Cookie("visitor", user);
            visitor.setPath("/");
            visitor.setHttpOnly(true);          // JavaScriptから読めなくする
            visitor.setAttribute("SameSite", "Lax");   // 他サイトからの送信を止める
            // HTTPSで動かすなら visitor.setSecure(true) も付ける
            response.addCookie(visitor);

            write(response, user, currentCart(session));
            return;
        }

        if (path.startsWith("/logout")) {
            HttpSession existing = request.getSession(false);
            if (existing != null) {
                existing.invalidate();   // 値を消すだけでは、同じIDのセッションが残る
            }
            write(response, "anonymous", new ArrayList<>());
            return;
        }

        HttpSession session = request.getSession();
        String item = request.getParameter("item");
        if (item != null && !item.isBlank()) {
            List<String> cart = currentCart(session);
            cart.add(item);
            // 変更したリストを入れ直す（分散セッションでは入れ直しが複製の合図になる）
            session.setAttribute(CART, cart);
        }
        write(response, currentUser(session), currentCart(session));
    }

    @SuppressWarnings("unchecked")
    private static List<String> currentCart(HttpSession session) {
        Object stored = session.getAttribute(CART);
        return stored instanceof List<?> ? (List<String>) stored : new ArrayList<>();
    }

    private static String currentUser(HttpSession session) {
        Object stored = session.getAttribute(USER);
        return stored == null ? "anonymous" : stored.toString();
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
