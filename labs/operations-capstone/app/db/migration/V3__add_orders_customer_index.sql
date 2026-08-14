-- 参照専用。すでに本番へ適用済み。一覧の絞り込みに使う。
CREATE INDEX ix_orders_customer ON orders (customer_id, order_id DESC);
