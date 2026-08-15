# JDBCの後始末 lab

実PostgreSQLへ接続して、DAOの値の渡し方と後始末を直します。
Web・Jakarta EE編『JDBCとデータベース』の「章末演習：JDBC」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDK・Maven・DockerまたはPodman・`postgres:16-alpine` image。

```bash
docker pull postgres:16-alpine     # Podmanなら podman pull postgres:16-alpine
```

## 何をするか

`exercise/CustomerDao.java` を直します。いまは値を文字列に埋め込み、開いた接続を閉じず、
複数件の登録を1件ずつ確定しています。手元のテストデータでは動いて見えます。

| 検査 | 実DBで起きること |
|---|---|
| `jdbc-crud` | 登録した1件を読み戻せる（ここはひな形でも通る） |
| `jdbc-injection` | 表示名に混ぜた `DROP TABLE` が実行され、表そのものが消える |
| `jdbc-no-leak` | 閉じ忘れた接続が枠を使い切り、`too many clients already`になる |
| `jdbc-rollback` | 途中で失敗すると、半分だけ登録された状態が残る |

## 接続の閉じ忘れを、どうやって測るか

閉じ忘れはコードを読んでも見落とします。かといって「いま何本開いているか」を数える方法では
測れません。参照が切れた接続はGCで回収され、ソケットも閉じられるので、数える時点には
消えていることがあります（このlabを作るときに実測しました。8本漏らした直後に残っていたのは5本、
`System.gc()` のあとは0本でした）。

そこで、閉じ忘れが**本番で表に出るのと同じ形**で測ります。このlabのPostgreSQLは
`max_connections=10` で動いていて、DAOを60回呼びます。閉じていれば同時に1〜2本しか
使わないので通りますが、漏らしていれば途中で `too many clients already` になります。

実測では、ひな形は60回のうち32〜37回が接続できず、模範解答は0回です。
参考値として `pg_stat_activity` の本数も出しますが、上に書いた理由で判定には使いません。

## SQLインジェクションは本当に実行させる

表示名として `x'); DROP TABLE customer; --` を渡します。値を文字列に埋め込んでいれば、
`INSERT` のあとに `DROP TABLE` が続く1本の命令になり、**表が実際に消えます**。
消えたかどうかは `information_schema.tables` を見て判定します。

`PreparedStatement` でプレースホルダに渡せば、値の中に何が入っていてもSQLとしては
解釈されず、この文字列がそのまま保存されます。エスケープを自分で書くのは、
抜けたときに気づけないので解決になりません。

## Mavenはドライバを取り出すためだけに使う

buildもtestもMavenには任せていません。`mvn dependency:copy-dependencies` で
PostgreSQLのJDBCドライバのjarを `out/lib` へ取り出し、あとは `javac` と `java` を直接使います。

クラス名・コンストラクタ・`insert` / `findNameByEmail` / `insertAll` の形は
採点の足場が呼ぶので変えないでください。
