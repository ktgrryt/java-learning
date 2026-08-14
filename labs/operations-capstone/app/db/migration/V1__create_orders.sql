-- 参照専用。すでに本番へ適用済み。
CREATE TABLE orders (
    order_id    BIGINT      PRIMARY KEY,
    customer_id BIGINT      NOT NULL,
    store_id    BIGINT      NOT NULL,
    amount      INTEGER     NOT NULL,
    status      VARCHAR(16) NOT NULL
);
