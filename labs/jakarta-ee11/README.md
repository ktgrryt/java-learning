# Jakarta EE 11実サーバーラボ

Jakarta REST、Bean Validation、recordのJSON変換を、教材用ミニAPIではなく
Jakarta EE 11対応サーバーで確認する最小WARです。

## 必要なもの

- JDK 21以降
- Maven 3.9以降
- **Jakarta EE 11 Platform対応サーバー**（Open Liberty、WildFly、Payara など）
- 初回のみ、依存をダウンロードするためのネットワーク

> この環境にはアプリサーバーが無いため、デプロイ後の応答は未確認です。
> `mvn clean package` までは同じ構成で通ります。

API JARは `provided` であり、実装はサーバーが提供します。Tomcatは
フルのJakarta EEサーバーではないので、Servlet以外の仕様には対応サーバーが必要です。

## 手順

```sh
mvn clean package
```

`target/jakarta-ee11-lab.war` ができます。これをサーバーへデプロイし、
コンテキストパスが `jakarta-ee11-lab` なら次を試します。

```sh
curl http://localhost:8080/jakarta-ee11-lab/api/health

curl -i -H 'Content-Type: application/json' \
  -d '{"name":"Aki","email":"aki@example.test"}' \
  http://localhost:8080/jakarta-ee11-lab/api/users

curl -i -H 'Content-Type: application/json' \
  -d '{"name":"","email":"invalid"}' \
  http://localhost:8080/jakarta-ee11-lab/api/users
```

## 成功したらこう出る

- 1つ目 … 200。ヘルスチェックの応答が返る
- 2つ目 … 201（または200）。recordがJSONへ変換されて返る
- 3つ目 … **400**。`name` が空、`email` が形式違反なので Bean Validation が弾く

3つ目が400になることが、このラボの中心です。200が返るなら検証が効いていません。

## 確認すること

1. 3つ目の要求が400になり、どの項目が悪いかが応答に含まれるか
2. recordがJSONへ変換されるか（getterを書かなくても変換されること）
3. サーバーログへ要求本文の機密値を不用意に出していないか
4. URLやデプロイ方法は使用サーバーに合わせて変更すること

生成物（`target/`）はコミットしません。
