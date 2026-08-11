package org.junit.jupiter.params;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 値を変えて同じ検証を繰り返すテストの印。値の並びは {@code @CsvSource} で渡す。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ParameterizedTest {
}
