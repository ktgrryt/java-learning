package cafe.web;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 注文の受付と照会をするServlet。
 *
 * <p>Servletは<b>1つのインスタンスが複数の要求を同時に処理します</b>。
 * いまの実装は1人で試すと動きますが、同時に使われると壊れます。
 * さらに応答の作り方にも問題があります。実サーバーへ当てて4つを別に測ります。
 *
 * <p>満たすこと:
 *
 * <ul>
 *   <li>{@code GET /orders?name=X} … 200。本文は{@code <p>ようこそ X さん</p>}。
 *       {@code X}はHTMLとして解釈させない（そのまま埋め込まない）</li>
 *   <li>{@code POST /orders}（フォームの{@code item}） … 201。
 *       {@code Location}ヘッダへ{@code /orders/<採番したID>}を入れる</li>
 *   <li>{@code GET /orders/<ID>} … あれば200で{@code <p>注文 ID: 品名</p>}、
 *       無ければ<b>404</b>で{@code <p>見つかりません</p>}</li>
 *   <li>本文は日本語をそのまま返せる文字コードで書き出す</li>
 * </ul>
 *
 * <p>クラス名・URLのマッピングは採点の足場が使うので変えないこと。
 */
@WebServlet("/orders/*")
public class OrderServlet extends HttpServlet {

    private final OrderStore store = new OrderStore();

    /** いま処理している要求の名前。 */
    private String currentName;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // TODO: 要求ごとの値をインスタンスフィールドへ置くと、同時に来た要求と混ざる
        currentName = request.getParameter("name");

        // 実際の処理には時間がかかる（DB照会・外部API呼び出しなど）
        sleepQuietly();

        // TODO: 日本語をそのまま返せる文字コードを指定する
        response.setContentType("text/html");

        String path = request.getPathInfo();
        if (path != null && path.length() > 1) {
            // TODO: 見つからないときのステータスコードを見直す
            String id = path.substring(1);
            String item = store.find(id).orElse("見つかりません");
            response.getWriter().write("<p>注文 " + id + ": " + item + "</p>");
            return;
        }

        // TODO: 受け取った値をそのままHTMLへ埋め込むと、HTMLとして解釈される
        response.getWriter().write("<p>ようこそ " + currentName + " さん</p>");
    }

    // TODO: POSTを受け付ける（いまはコンテナが405を返す）

    /** 「重い処理」の代わり。消さないこと（同時に処理される状況を作るために使います）。 */
    private static void sleepQuietly() {
        try {
            Thread.sleep(60);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
