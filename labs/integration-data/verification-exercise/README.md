# 本番同等DBで検証する lab

後片付けの方式（ロールバック / truncate）を、テストの内容に合わせて選びます。
本番運用・セキュリティ編『実DB・スキーマ移行・非同期連携』の「本番同等DBで検証する」が採点に使います。

```
JQ_LAB_PORT=15432 JQ_LAB_RUN_ID=manual JQ_CONTAINER_RUNTIME=podman sh run-runtime-lab.sh
```

PostgreSQL 16 のコンテナを起動します。Docker と Podman のどちらでも動きます。

## 何をするか

`exercise/strategy.properties` に、2つのテストへ方式を割り当て、1つの観察結果を記録します。

| 方式 | 中身 | 向いている検証 |
|---|---|---|
| `rollback` | トランザクションを開いて検証し、最後に`ROLLBACK` | 速い。コミットを必要としない検証 |
| `truncate` | 実際に`COMMIT`してから検証し、後片付けに`TRUNCATE` | 遅い。本番と同じ経路を通る検証 |

## 正解表はありません

割り当てを間違えると、**実際のPostgreSQLがそのテストを落とします**。

- 別の接続から見えることを確かめるテストを`rollback`にすると、外からは何も見えず0件になります。
  `psql`の呼び出し1回が接続1つなので、これは本物の「別接続」です。
- 遅延させた一意制約（`DEFERRABLE INITIALLY DEFERRED`）のもとで「途中の重複が許される」ことを
  確かめるテストを`truncate`にすると、コミットの瞬間に制約が検査されて違反で落ちます。

つまり「どちらか一方に統一しよう」とすると、必ずどちらかが検証できなくなります。
これがこのレッスンの主題です。

## 観察するもの

PostgreSQLの採番（`BIGSERIAL`）はトランザクションの外側で進みます。ロールバックしても戻らないので、
IDには欠番ができます。「テストのたびにIDが1から始まる」と決めつけた検証は、ここで壊れます。
実際にどうなるかを測った結果と、記録した値を突き合わせます。

## ディレクトリ

```
db/schema.sql                 検証用のスキーマ（参照専用）
exercise/strategy.properties  割り当てと観察を書く
reference/strategy.properties 模範解答
```
