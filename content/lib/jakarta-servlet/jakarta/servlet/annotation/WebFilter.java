package jakarta.servlet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このフィルタが割り込むURLを宣言する。
 *
 * <pre>
 * &#64;WebFilter("/*")        // 全てのリクエストに割り込む
 * &#64;WebFilter("/admin/*")  // /admin 以下だけ
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WebFilter {

    /** 対応するURLパターン。末尾の {@code /*} で「以下すべて」を表せる。 */
    String value() default "";

    String[] urlPatterns() default {};

    String filterName() default "";
}
