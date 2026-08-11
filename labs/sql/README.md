# SQLラボ

`schema.sql` はH2 2.xとPostgreSQLで試しやすい標準寄りのSQLです。制約・JOIN・実行計画を
実際のDBで確かめます。

## 必要なもの

次のどちらかを用意します。**この教材はDBを同梱・自動起動しません。**

- **H2 2.x**（手軽。JARひとつで動きます）
  ```bash
  # h2-*.jar を入手して、ファイルDBに対してシェルを開く
  java -cp h2-2.*.jar org.h2.tools.Shell -url "jdbc:h2:./lab" -user sa -password ""
  ```
- **PostgreSQL 14以降**（本番に近い）
  ```bash
  createdb sqllab
  psql sqllab -f schema.sql
  ```

> この環境にはH2もPostgreSQLも無いため、以下の出力は `schema.sql` の内容から
> 導いたものです（実行して採取したものではありません）。

**本番DBへは適用しないでください。**

## 成功したらこう出る

`schema.sql` の末尾のSELECTは、顧客ごとの「PAIDの合計」を返します。
`Sora` には注文が無いので、`LEFT JOIN` と `COALESCE` により0で残ります。

| name | paid_total |
|---|---|
| Aki | 1200.00 |
| Mina | 2500.00 |
| Sora | 0.00 |

（桁の表示のしかたはクライアントによって変わります）

`Aki` の注文102は `NEW` なので合計に入りません。ここが「`WHERE` ではなく `ON` に
絞り込みを書く」効き目です。

## 確認すること

1. 存在しない顧客IDの注文をINSERTし、**外部キー違反** になること
   （`fk_orders_customer`）
2. 負の金額をINSERTし、**CHECK制約違反** になること（`total >= 0`）。
   `status` に `'DONE'` のような未定義の値を入れても同じく弾かれること
3. 同じメールアドレスの顧客をINSERTし、**UNIQUE違反** になること
4. 集約SELECTを `EXPLAIN` し、データ量を増やす前後で計画を比べること
5. `status, created_at` の複合インデックスを追加し、代表SQLで効果を測ること
6. 末尾のSELECTの `AND o.status = 'PAID'` を `WHERE` へ移すと、`Sora` が消えて
   内部結合に近い結果になること（アプリ内のレッスンと同じ論点です）

DB製品ごとに実行計画や日時・自動採番の構文は異なります。使う製品の公式文書も
合わせて確認してください。
