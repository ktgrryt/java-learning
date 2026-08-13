CREATE TABLE customers (
    id BIGINT,
    name VARCHAR(100),
    email VARCHAR(255)
);

CREATE TABLE orders (
    id BIGINT,
    customer_id BIGINT,
    status VARCHAR(20),
    total DECIMAL(12, 2),
    created_at TIMESTAMP WITH TIME ZONE
);
