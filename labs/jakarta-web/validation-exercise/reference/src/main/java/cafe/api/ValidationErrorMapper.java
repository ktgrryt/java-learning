package cafe.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Comparator;
import java.util.List;

/**
 * 制約違反を、呼び出し側が直せる応答へ変える（模範解答）。
 *
 * <p>要点は3つ。
 *
 * <ul>
 *   <li><b>どの項目が悪いのかを返す。</b>400だけでは、呼び出し側は何を直せばよいか分からない。</li>
 *   <li><b>内部の作りは返さない。</b>{@code getPropertyPath()}は
 *       {@code create.arg0.quantity}のようにメソッド名と引数の位置まで含む。
 *       そのまま返すと、実装の形が外から見えてしまう。末尾の項目名だけを取る。</li>
 *   <li><b>順番を決める。</b>違反の順番は保証されない。並べておかないと、
 *       呼び出し側は応答を当てにしたテストを書けない。</li>
 * </ul>
 *
 * <p>例外の型名やスタックトレースを本文へ入れないこと。攻撃の手がかりになる。
 */
@Provider
public class ValidationErrorMapper implements ExceptionMapper<ConstraintViolationException> {

    /** 応答の形。JSON-Bがrecordをそのまま書き出す。 */
    public record FieldError(String field, String message) {
    }

    public record Errors(List<FieldError> errors) {
    }

    @Override
    public Response toResponse(ConstraintViolationException violations) {
        List<FieldError> errors = violations.getConstraintViolations().stream()
                .map(violation -> new FieldError(lastNode(violation), violation.getMessage()))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Errors(errors))
                .build();
    }

    /** {@code create.arg0.quantity} から {@code quantity} だけを取り出す。 */
    private static String lastNode(ConstraintViolation<?> violation) {
        String name = "";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }
}
