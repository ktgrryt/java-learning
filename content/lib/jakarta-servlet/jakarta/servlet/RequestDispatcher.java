package jakarta.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 処理を別のサーブレットやビューへ渡す道具。
 *
 * <p><b>forward とリダイレクトの違い</b>（第23章）
 * <ul>
 *   <li>{@code forward} … <b>サーバの中で</b>処理を渡す。ブラウザは知らないのでURLは変わらず、
 *       リクエストスコープの値もそのまま引き継がれる。リクエストは1回</li>
 *   <li>{@code sendRedirect} … ブラウザに「そっちへ行き直して」と指示する。URLが変わり、
 *       リクエストスコープは<b>失われる</b>。リクエストは2回</li>
 * </ul>
 */
public interface RequestDispatcher {

    /** サーバ内部で処理を渡す。URLは変わらず、リクエストスコープも保たれる。 */
    void forward(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException;

    /** 別のサーブレットの出力を、いまの本文の途中に差し込む。 */
    void include(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException;
}
