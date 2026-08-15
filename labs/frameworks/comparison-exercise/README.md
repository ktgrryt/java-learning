# 3製品の成果物を見比べる演習

業務フレームワーク編『3製品の設計思想と選定』の「章末演習：業務フレームワークの選択肢」が採点に使います。

同じ注文APIを Spring Boot・Open Liberty・Quarkus の3製品で用意してあります。3つを実際にbuildし、
**出来た成果物を見比べて**、配備の形の違いを埋める演習です。

編集するのは `exercise/comparison.properties` だけで、`reference/` が模範解答です。3製品のソースと
pomは変更しません。

## 時間やメモリは測りません

build時間・起動時間・RSSは、機械の性能や状態で大きく変わります。合否の条件にすると
「速いマシンなら合格」になってしまうので、この演習では**成果物の構造**だけを見ます。時間の比較は
自分の環境で `mvn package` を何度か回して、傾向として掴んでください。

## 見るところ

```sh
jar tf spring/target/orders-spring.jar | grep -E 'BOOT-INF/|loader/' | head
jar tf liberty/target/orders-liberty.war
cat liberty/src/main/liberty/config/server.xml
ls quarkus/target/quarkus-app && ls quarkus/target/quarkus-app/lib
```

| 見るところ | 何が分かるか |
|---|---|
| `BOOT-INF/lib` と起動用loader | 実装を成果物へ同梱し、単体で起動する形 |
| `WEB-INF/lib` が空 ＋ `server.xml` のFeature宣言 | 実装はサーバーが提供し、WARだけを載せる形 |
| `quarkus-app/` の `quarkus-run.jar` と `lib/` | 一式そろって初めて起動できる形 |

## 必須条件を先に適用する

`comparison.properties` の先頭には前提が書いてあります。

```properties
constraint.implementation-provided-by-server=required
```

点数を足して合計が高いものを選ぶ前に、**この条件を満たさない候補を外す**のが順番です。3製品の
どれが外れるかは、成果物を見れば分かります。採点は答えを表示しません。実測した事実を根拠として
`decision.reason` へ書いてください。

生成物（`*/target/` `out/`）はコミットしません。
