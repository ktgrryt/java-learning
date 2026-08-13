# SQLラボ

第46章の必須`runtime-lab`です。画面から提出すると、一時的なPostgreSQL 16 containerへ
学習者が編集したDDLとSQLを適用し、制約・JOIN・集約・HAVING・実行計画を直接検証します。
実行ごとに一意なcontainer名と動的なlocalhost portを使い、終了時にcontainerを削除します。

## 必要なもの

自動採点にはDockerまたはPodmanと、選んだruntime側へ事前に取得したimageが必要です。
両方が使える場合は、必要imageがある方を自動選択します。採点中にimageを自動取得しません。

```bash
# Dockerを使う場合
docker pull postgres:16-alpine
docker image inspect postgres:16-alpine

# Podmanを使う場合
podman pull postgres:16-alpine
podman image inspect postgres:16-alpine
```

手元でSQLだけを試す場合は、次のどちらかを利用できます。

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

**本番DBへは適用しないでください。**

## 成功したらこう出る

`schema.sql` の末尾のSELECTは、顧客ごとの「PAIDの合計」を返します。自動採点版では
`exercise/paid_totals.sql`を編集し、同じ結果を実DBから得ます。
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
