ALTER TABLE orders ADD COLUMN cancel_reason VARCHAR(100);
ALTER TABLE orders ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE order_outbox (
    event_id VARCHAR(100) PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
