-- 顧客labの土台。参照専用（採点のたびに作り直される）。
--
-- emailを主キーにしてあるので、同じemailの2件目は必ず失敗する。
-- 「途中で失敗したときに何が残るか」を測るのに使う。

DROP TABLE IF EXISTS customer;

CREATE TABLE customer (
    email        text PRIMARY KEY,
    display_name text NOT NULL
);
