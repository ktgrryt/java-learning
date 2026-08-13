CREATE INDEX idx_orders_status_created_at
    ON orders (status, created_at);

ANALYZE orders;

EXPLAIN (ANALYZE, FORMAT JSON)
SELECT id, total
FROM orders
WHERE status = 'CANCELLED'
  AND created_at >= TIMESTAMPTZ '2030-01-01 00:00:00+00';
