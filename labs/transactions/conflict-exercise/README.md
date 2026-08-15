# 送金トランザクション lab

実PostgreSQLへ接続して、送金のトランザクションを直します。
Web・Jakarta EE編『トランザクションと整合性』の「同時更新・再試行・冪等性」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDK・Maven・DockerまたはPodman・`postgres:16-alpine` image。

```bash
docker pull postgres:16-alpine     # Podmanなら podman pull postgres:16-alpine
```

## 何をするか

`exercise/TransferService.java` を直します。いまは1文ずつ別々に流していて（autocommitのまま）、
残高を読んで計算して書き戻しています。1スレッドで成功する分には動いて見えますが、
実DBでは4つの問題が出ます。

| 検査 | 実DBで起きること |
|---|---|
| `tx-rollback` | 存在しない口座へ送ると、送金元だけ減って戻らない（`UPDATE`は0行更新でもエラーにならない） |
| `tx-lost-update` | 同じ向きの同時送金で、読んで計算して書く間の更新が上書きされる |
| `tx-crossing` | 逆向きの同時送金がぶつかる（順序をそろえるか、衝突を捕まえて再試行する） |
| `tx-idempotent` | 同じ送金IDが2回届くと、残高だけ二重に動く |

判定は必ず**DBを読んで**行います（serviceの戻り値や例外だけでは、実際に何が残ったか分かりません）。
場面ごとに `db/schema.sql` で表を作り直し、口座A・Bへ100,000ずつ入れてから始めます。

## 制約はアプリの外側にも置いてある

`db/schema.sql`（参照専用）が、アプリの実装によらず守ります。

- `balance >= 0` … 残高は負にならない
- `transfer.id` が PRIMARY KEY … 同じ送金IDは1回しか記録できない（2回目は必ず衝突する）
- `REFERENCES account(id)` … 存在しない口座は記録できない

## Mavenはドライバを取り出すためだけに使う

buildもtestもMavenには任せていません。`mvn dependency:copy-dependencies` で
PostgreSQLのJDBCドライバのjarを `out/lib` へ取り出し、あとは `javac` と `java` を直接使います。
検査の粒度を自分で持てるようにするためと、テスト実行プラグインのダウンロードに依存しないためです。

初回はドライバのダウンロードに時間がかかることがあります。

## 直し方の手がかり

- 1件の送金を1つのトランザクションにする（`setAutoCommit(false)` → 成功で`commit`、失敗で`rollback`）
- 金額の計算はDBに任せる（`SET balance = balance + ?`）。Javaで計算して書き戻すと、
  その間に入った送金の結果を上書きする
- `executeUpdate()` の戻り値（更新できた行数）を確かめる
- 送金の記録を**残高より先に**入れる。2回目はそこで弾かれるので、残高は動かない
- 逆向きの同時送金は、口座IDの順で更新するか、`SQLState` の `40001` / `40P01` を捕まえて再試行する

クラス名・コンストラクタ・`transfer(String, String, String, int)` の形は採点の足場が呼ぶので
変えないでください。
