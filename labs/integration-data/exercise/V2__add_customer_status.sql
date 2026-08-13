-- TODO: 旧applicationのINSERTを壊さない形でstatus列を追加する。
ALTER TABLE customer ADD COLUMN status VARCHAR(20) NOT NULL;
