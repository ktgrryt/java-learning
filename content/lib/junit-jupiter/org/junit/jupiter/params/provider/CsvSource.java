package org.junit.jupiter.params.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @ParameterizedTest} へ渡す値の表。1要素が1回ぶんの引数で、カンマ区切り。
 *
 * <p>例：{@code @CsvSource({"1000,20,800", "500,100,0"})} なら2回実行される。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CsvSource {
    String[] value();
}
