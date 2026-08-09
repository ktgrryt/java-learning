# Jakarta EE 11実サーバーラボ

Jakarta REST、Bean Validation、recordのJSON変換を、教材用ミニAPIではなく
Jakarta EE 11対応サーバーで確認する最小WARです。

必要なもの:

- JDK 21
- Maven 3.9以降
- Jakarta EE 11 Platform対応サーバー

```sh
mvn clean package
```

生成された`target/jakarta-ee11-lab.war`をサーバーへデプロイし、コンテキストパスが
`jakarta-ee11-lab`なら次を試します。

```sh
curl http://localhost:8080/jakarta-ee11-lab/api/health
curl -i -H 'Content-Type: application/json' \
  -d '{"name":"Aki","email":"aki@example.test"}' \
  http://localhost:8080/jakarta-ee11-lab/api/users
curl -i -H 'Content-Type: application/json' \
  -d '{"name":"","email":"invalid"}' \
  http://localhost:8080/jakarta-ee11-lab/api/users
```

最後の要求が400になること、recordがJSONへ変換されること、サーバーログへ要求本文の
機密値を不用意に出していないことを確認します。URLやデプロイ方法は使用サーバーに合わせて
変更してください。API JARは`provided`であり、実装はサーバーが提供します。
