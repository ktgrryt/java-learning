package jakarta.enterprise.event;

/** 学習用の最小インターフェース。本物ではコンテナが実装を注入する。 */
public interface Event<T> { void fire(T event); }
