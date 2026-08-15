# JPAの問い合わせとロック lab

実PostgreSQLへ接続して、関連の取り方・遅延読み込み・同時更新を直します。
Web・Jakarta EE編『Jakarta Persistence (JPA)』の「JPQL・関連・N+1とロック」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDK・Maven・DockerまたはPodman・`postgres:16-alpine` image。

```bash
docker pull postgres:16-alpine     # Podmanなら podman pull postgres:16-alpine
```

Mavenで取り出すのはHibernate（JPAの実装）とJDBCドライバです。サーバーは使いません
（Java SEからJPAを直接動かします）。初回はダウンロードに時間がかかります。

## 何をするか

編集するのは2ファイルです。

| ファイル | やること |
|---|---|
| `Customer.java` | `version`列を同時更新の検出に使わせる（宣言を1つ） |
| `CustomerRepository.java` | 関連をまとめて取る／閉じる前に値をそろえる |

| 検査 | 実DBで起きること |
|---|---|
| `jpa-n-plus-one` | 顧客5人で**SQLが6本**飛ぶ（1本＋5本） |
| `jpa-lazy-outside` | 閉じたあとに注文を触って`LazyInitializationException` |
| `jpa-optimistic-lock` | 2つ目の更新が**黙って上書き**する（更新の喪失） |

## N+1は出力を見ても分からない

結果は正しいので、テストが通ってしまいます。違いは**発行されたSQLの本数**だけです。
この lab ではHibernateの統計から本数を数えます（顧客5人・注文15件で、
ひな形は6本、模範解答は1本）。

件数が少ないうちは誰も気づきません。データが増えてから「なぜか遅い」になります。

## 遅延読み込みは「あとで取ってくる」だけ

`EntityManager`を閉じたあとには取ってこられません。閉じたあとも使う値は、
閉じる前に入れ物（`CustomerSummary`）へ移しておきます。

## version列はあるのに使われていない

`db/schema.sql`には最初から`version`列があります。しかし宣言しなければ、ただの整数の列です。
同じ行を2つのトランザクションが読んで両方が書くと、あとの書き込みが黙って勝ちます。

宣言すると、JPAは更新のたびに `UPDATE ... WHERE id = ? AND version = ?` を発行し、
更新できた行数を見ます。0行なら「読んだあとに誰かが変えた」ということなので例外になります。
行をロックして待たせる（悲観ロック）のではなく、**ぶつかったときに気づく**方式です。

## 参照専用のファイル

`CustomerOrder.java`・`CustomerSummary.java`・`persistence.xml`・`db/schema.sql`・
`pom.xml`・`JpaHarness.java` は編集しません。
`findAllWithOrders()` と `findOne(long)` の名前・引数・戻り値も変えないでください。
