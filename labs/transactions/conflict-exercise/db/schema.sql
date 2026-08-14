-- 送金labの土台。参照専用（採点のたびに作り直される）。
--
-- 制約はアプリの外側に置く。アプリが何回書き直されても、DBが最後の砦として守る。
--   * 残高は負にならない（CHECK）
--   * 送金IDは1回しか使えない（PRIMARY KEY）→ 2回目の送金は必ず衝突する
--   * 存在しない口座へは記録できない（REFERENCES）

DROP TABLE IF EXISTS transfer;
DROP TABLE IF EXISTS account;

CREATE TABLE account (
    id      text    PRIMARY KEY,
    balance integer NOT NULL CHECK (balance >= 0)
);

CREATE TABLE transfer (
    id           text    PRIMARY KEY,
    from_account text    NOT NULL REFERENCES account(id),
    to_account   text    NOT NULL REFERENCES account(id),
    amount       integer NOT NULL CHECK (amount > 0)
);
