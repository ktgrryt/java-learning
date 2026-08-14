# 運用統合演習 lab

配備のあとに起きた「遅くなった」「失敗する」「入れ替えが成功と誤判定される」を、
1つのプロジェクトの中で立て直します。第63章の章末演習が採点に使います。

```
./run-tests.sh              # 自分の実装を採点する（app）
./run-tests.sh reference    # 模範解答で採点する（13件すべて成功する）
```

依存はありません。JDKだけで動きます。`javac` と `java` は一時ディレクトリで実行し、
`lab.root` に採点対象のディレクトリを渡します。

## 何をするか

`requirements.md` を読んでから、`app/` の6ファイルを直します。
`app/incident/` のログと配備一覧が、判断の材料です。

## 速さをどう測るか

秒では測りません。走らせた機械やその時の負荷で結果が変わり、同じ実装が通ったり落ちたり
するためです。ここでは**問い合わせを何回投げたか**（`CountingOrderQueryPort`）と、
**待つつもりだったミリ秒**（`RecordingSleeper`）を数えます。1件ずつ引く実装と
まとめて引く実装の差は回数にそのまま出るので、どの機械でも同じ結果になります。

## ディレクトリ

```
app/
  api/openapi-v1.json          利用側と約束しているAPI契約（参照専用）
  db/migration/V1〜V3          すでに本番へ適用済み（参照専用）
  db/migration/V4              これから当てる移行（編集する）
  incident/orders.log          障害時のログ（参照専用）
  incident/deployments.txt     直近の配備（参照専用）
  src/main/java/cafe/ops/      実装。編集するのは3ファイル
  src/test/java/cafe/ops/      受け入れ条件（変更できない）
  RUNBOOK.md / ADR.md          引き継ぎの成果物（編集する）
reference/                     模範解答
```
