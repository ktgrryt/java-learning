package cafe.web;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * 注文の受付と照会をするServlet（模範解答）。
 *
 * <p>要点は4つ。
 *
 * <ul>
 *   <li><b>要求ごとの値はインスタンスフィールドへ置かない。</b>Servletは1つのインスタンスが
 *       複数の要求を同時に処理するので、フィールドは全要求で共有される。
 *       ローカル変数（＝スレッドごとに別）に持てば混ざらない。
 *       フィールドに置けるのは、要求に依存しない・不変・スレッドセーフなものだけ。</li>
 *   <li><b>文字コードを指定する。</b>指定しないと既定の文字コードで書き出され、
 *       日本語が壊れる。{@code setContentType}へcharsetまで書く（本文を書く前に）。</li>
 *   <li><b>受け取った値をHTMLへ埋め込む前にエスケープする。</b>しないと、
 *       値に書いたタグやスクリプトがブラウザで実行される（XSS）。</li>
 *   <li><b>状態に合ったステータスコードを返す。</b>無いものは404、作ったら201と
 *       {@code Location}。本文だけ「見つかりません」にして200を返すと、
 *       呼び出し側は成功と区別できない。</li>
 * </ul>
 */
@WebServlet("/orders/*")
public class OrderServlet extends HttpServlet {

    /** 置き場自体はスレッドセーフ。要求に依存しないのでフィールドに持ってよい。 */
    private final OrderStore store = new OrderStore();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // 要求ごとの値はローカル変数へ。ここが混ざらないことの決め手
        String name = request.getParameter("name");

        sleepQuietly();

        // 本文を書く前に、文字コードまで指定する
        response.setContentType("text/html; charset=UTF-8");

        String path = request.getPathInfo();
        if (path != null && path.length() > 1) {
            String id = path.substring(1);
            Optional<String> item = store.find(id);
            if (item.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("<p>見つかりません</p>");
                return;
            }
            response.getWriter().write("<p>注文 " + escape(id) + ": " + escape(item.get()) + "</p>");
            return;
        }

        response.getWriter().write("<p>ようこそ " + escape(name) + " さん</p>");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // 受け取る側の文字コードも決めておく（フォームの日本語が壊れないように）
        request.setCharacterEncoding("UTF-8");
        String item = request.getParameter("item");
        response.setContentType("text/html; charset=UTF-8");

        if (item == null || item.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("<p>品名が必要です</p>");
            return;
        }

        String id = store.add(item);
        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setHeader("Location", "/orders/" + id);
        response.getWriter().write("<p>受け付けました: " + escape(id) + "</p>");
    }

    /** HTMLとして解釈される文字を実体参照へ置き換える。 */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(60);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
