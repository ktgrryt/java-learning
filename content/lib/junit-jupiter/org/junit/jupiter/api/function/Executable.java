package org.junit.jupiter.api.function;

/**
 * 「あとで実行する処理」1つ。{@code assertThrows} へラムダを渡すための型。
 *
 * <p>{@code throws Throwable} なのが大事なところ。検査例外を投げる処理でも
 * {@code () -> ...} のまま書けるので、テスト側に余計な try-catch が要らない。
 */
@FunctionalInterface
public interface Executable {
    void execute() throws Throwable;
}
