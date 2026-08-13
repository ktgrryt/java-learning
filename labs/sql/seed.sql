INSERT INTO customers (id, name, email) VALUES
    (1, 'Aki', 'aki@example.test'),
    (2, 'Mina', 'mina@example.test'),
    (3, 'Sora', 'sora@example.test');

INSERT INTO orders (id, customer_id, status, total, created_at) VALUES
    (101, 1, 'PAID', 1200.00, '2025-01-01 10:00:00+00'),
    (102, 1, 'NEW',   800.00, '2025-01-02 11:00:00+00'),
    (103, 2, 'PAID', 2500.00, '2025-01-03 12:00:00+00');
