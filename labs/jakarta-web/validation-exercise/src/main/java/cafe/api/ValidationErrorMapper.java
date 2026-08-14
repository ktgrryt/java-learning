package cafe.api;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * 制約違反を、呼び出し側が直せる応答へ変える。
 *
 * <p>いまは何もしていないので、既定の応答がそのまま返ります。既定の応答は
 * <b>どの項目が悪いのか分かりません</b>。逆に、例外の内容をそのまま返すのも危険です
 * （内部の型名やメソッド引数の名前が漏れます）。
 *
 * <p>返す形（このとおりに作ること）:
 *
 * <pre>{"errors":[{"field":"item","message":"..."},{"field":"quantity","message":"..."}]}</pre>
 *
 * <ul>
 *   <li>ステータスは400</li>
 *   <li>{@code field}は<b>項目名だけ</b>。{@code create.arg0.quantity}のような
 *       内部の経路をそのまま出さない</li>
 *   <li>{@code field}の昇順に並べる（順番が毎回変わると、呼び出し側がテストを書けない）</li>
 *   <li>例外の型名やスタックトレースは入れない</li>
 * </ul>
 */
@Provider
public class ValidationErrorMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException violations) {
        // TODO: 400と、上の形のJSONを返す
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(violations.toString())
                .build();
    }
}
