package jakarta.servlet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このサーブレットが応答するURLを宣言する。
 *
 * <pre>
 * &#64;WebServlet("/hello")
 * public class HelloServlet extends HttpServlet { ... }
 * </pre>
 *
 * 本物のアプリサーバは起動時にクラスを走査してこの注釈を見つけ、自動で登録する。
 * この教材では走査の代わりに {@code MiniWeb.app().register(HelloServlet.class)} と
 * 明示的に登録するが、パスの読み取り元はこの注釈で本物と同じ。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WebServlet {

    /** 対応するURLパターン（例 {@code "/hello"}）。 */
    String value() default "";

    /** {@code value} と同じ意味。本物では複数指定できる。 */
    String[] urlPatterns() default {};

    /** サーブレットの名前。省略するとクラス名が使われる。 */
    String name() default "";
}
