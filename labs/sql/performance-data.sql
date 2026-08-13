INSERT INTO orders (id, customer_id, status, total, created_at)
SELECT 1000 + n,
       1,
       'NEW',
       1.00,
       TIMESTAMPTZ '2020-01-01 00:00:00+00' + n * INTERVAL '1 second'
FROM generate_series(1, 20000) AS n;

INSERT INTO orders (id, customer_id, status, total, created_at)
VALUES (999999, 2, 'CANCELLED', 1.00, '2035-01-01 00:00:00+00');
