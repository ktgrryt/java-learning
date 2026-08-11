# 実DB・migrationラボ

このディレクトリは特定のDB製品を勝手に起動しません。業務で採用するDBの一時コンテナへ、
`db/migration` を番号順に適用するテストを作るための最小素材です。

## 必要なもの

- JDK 21以降
- DBの一時インスタンス（Docker/PodmanのPostgreSQL、またはH2 2.x）
- マイグレーションを適用する仕組み（Flyway / Liquibase / 自作のどれでも）
- Testcontainersを使う場合はコンテナランタイム

> この環境にはDBが無いため未実行です。以下は `db/migration` の内容から導いた
> 期待結果です。

## 入っているもの

| ファイル | 内容 |
|---|---|
| `V1__create_customer.sql` | `customer` テーブル（`email` に UNIQUE 制約） |
| `V2__add_customer_status.sql` | `status` 列を **既定値つきで** 追加（expand） |
| `V3__create_outbox.sql` | `outbox_event` テーブルと未送信を引く複合インデックス |

`V2` が `NOT NULL DEFAULT 'ACTIVE'` になっているのが要点です。既定値なしで
`NOT NULL` を足すと、その列を知らない旧アプリのINSERTが落ちます。

## 成功したらこう出る

- 空のDBへ `V1` → `V2` → `V3` を順に適用して、すべて成功する
- 適用後、`customer` は `id` `email` `display_name` `created_at` `status` の5列になる
- 同じマイグレーションをもう一度流しても、適用済みとして飛ばされる（二重適用されない）
- `email` が重複するINSERTは `uq_customer_email` で拒否される

## 確認項目

1. 空DBへV1から全migrationを適用できる
2. V1時点のapplicationとV2時点のapplicationがrolling deploy中に共存できる
   （`V2` を適用してから新アプリを出す順序を守る）
3. emailの重複がDB制約で拒否される
4. transaction rollback後にoutboxだけ残らない
   （業務データとoutboxを**同じトランザクション**で書く）
5. 本番相当件数でindexが使われ、pool接続総数がDB予算内に収まる

Testcontainers等を使う場合は、Dockerがない環境でtestを黙ってskipせず、CIの必須jobとして
別に見えるようにします。DB imageはtagだけでなく可能ならdigestも記録してください。
