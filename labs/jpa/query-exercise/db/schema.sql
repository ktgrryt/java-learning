-- JPA labの土台。参照専用（採点のたびに作り直される）。
--
-- version列は最初から用意してある。使うかどうかはマッピング側の宣言で決まる。
-- 「列はあるのに誰も使っていない」状態は、実際の現場でもよくある。

DROP TABLE IF EXISTS customer_order;
DROP TABLE IF EXISTS customer;

CREATE TABLE customer (
    id         bigserial PRIMARY KEY,
    name       text      NOT NULL,
    budget_yen integer   NOT NULL,
    version    integer   NOT NULL DEFAULT 0
);

-- order は予約語なので customer_order にしてある
CREATE TABLE customer_order (
    id          bigserial PRIMARY KEY,
    customer_id bigint    NOT NULL REFERENCES customer(id),
    item        text      NOT NULL
);
