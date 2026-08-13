-- expand: 旧applicationも動けるよう、まず既定値つきで追加する。
ALTER TABLE customer ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
