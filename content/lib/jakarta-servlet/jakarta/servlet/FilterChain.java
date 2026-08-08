package jakarta.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * フィルタの「次」を表す。
 *
 * 登録した順に数珠つなぎになっていて、最後がサーブレット本体。
 * {@link #doFilter} を呼べば次へ進み、呼ばなければそこで打ち切られる。
 */
public interface FilterChain {

    void doFilter(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException;
}
