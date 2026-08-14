-- 検証用のスキーマ。参照専用。
CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    email    VARCHAR(64) NOT NULL UNIQUE,
    status   VARCHAR(16) NOT NULL DEFAULT 'NEW'
);

-- 注文の確定と同じトランザクションで積む送信箱。
-- 別の接続から見えるのは、コミットのあとだけ。
CREATE TABLE order_outbox (
    event_id  BIGSERIAL PRIMARY KEY,
    order_id  BIGINT NOT NULL REFERENCES orders (order_id),
    published BOOLEAN NOT NULL DEFAULT FALSE
);

-- 席の仮押さえ。一意制約を「トランザクションの終わりまで遅延」させてある。
-- 途中で一時的に重複していても、コミットの瞬間に整合していればよい、という作り。
CREATE TABLE seat_hold (
    seat_no INT NOT NULL,
    holder  VARCHAR(32) NOT NULL,
    CONSTRAINT uq_seat_hold UNIQUE (seat_no) DEFERRABLE INITIALLY DEFERRED
);
