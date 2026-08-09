CREATE TABLE customers (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW', 'PAID', 'CANCELLED')),
    total DECIMAL(12, 2) NOT NULL CHECK (total >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_orders_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id)
);

INSERT INTO customers (id, name, email) VALUES
    (1, 'Aki', 'aki@example.test'),
    (2, 'Mina', 'mina@example.test'),
    (3, 'Sora', 'sora@example.test');

INSERT INTO orders (id, customer_id, status, total, created_at) VALUES
    (101, 1, 'PAID', 1200.00, '2025-01-01 10:00:00+00'),
    (102, 1, 'NEW',   800.00, '2025-01-02 11:00:00+00'),
    (103, 2, 'PAID', 2500.00, '2025-01-03 12:00:00+00');

SELECT c.name, COALESCE(SUM(o.total), 0) AS paid_total
FROM customers c
LEFT JOIN orders o
  ON o.customer_id = c.id
 AND o.status = 'PAID'
GROUP BY c.id, c.name
ORDER BY c.name;
