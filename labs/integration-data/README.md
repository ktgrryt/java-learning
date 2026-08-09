# 実DB・migrationラボ

このdirectoryは特定のDB製品を勝手に起動しません。業務で採用するDBの一時containerへ、
`db/migration` を番号順に適用するtestを作るための最小素材です。

確認項目:

1. 空DBへV1から全migrationを適用できる
2. V1時点のapplicationとV2時点のapplicationがrolling deploy中に共存できる
3. emailの重複がDB制約で拒否される
4. transaction rollback後にoutboxだけ残らない
5. 本番相当件数でindexが使われ、pool接続総数がDB予算内に収まる

Testcontainers等を使う場合は、Dockerがない環境でtestを黙ってskipせず、CIの必須jobとして
別に見えるようにします。DB imageはtagだけでなく可能ならdigestも記録してください。

