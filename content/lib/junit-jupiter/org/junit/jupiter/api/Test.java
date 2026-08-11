package org.junit.jupiter.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * テストメソッドの印。本物のJUnit 5と同じ名前・同じ使い方にしてある。
 *
 * <p>{@link RetentionPolicy#RUNTIME} が要る。テストランナーは実行時にリフレクションで
 * この印を探すので、既定の {@code CLASS} だと1件も見つからない。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
}
