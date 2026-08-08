package jakarta.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 学習用の最小版。実際の境界制御はJakarta EEコンテナが行う。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Transactional {
    TxType value() default TxType.REQUIRED;
    Class<? extends Throwable>[] rollbackOn() default {};
    Class<? extends Throwable>[] dontRollbackOn() default {};

    enum TxType { REQUIRED, REQUIRES_NEW, MANDATORY, SUPPORTS, NOT_SUPPORTED, NEVER }
}
