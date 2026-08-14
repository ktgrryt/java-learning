-- expand段階。足すだけで、この移行では消さない。
-- 入れ替え中は古い版のアプリも動いているので、既存行はNULL許可で受け入れる。
ALTER TABLE orders ADD COLUMN cancel_requested_at TIMESTAMP NULL;

-- キャンセル要求を1件ずつ記録する。同じ要求が二重に記録されないよう、
-- 注文と冪等キーの組を一意にする。
CREATE TABLE order_cancel_audit (
    audit_id        BIGINT      PRIMARY KEY,
    order_id        BIGINT      NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    attempts        INTEGER     NOT NULL,
    recorded_at     TIMESTAMP   NOT NULL,
    CONSTRAINT uq_order_cancel_audit_request UNIQUE (order_id, idempotency_key)
);

CREATE INDEX ix_order_cancel_audit_order ON order_cancel_audit (order_id);
