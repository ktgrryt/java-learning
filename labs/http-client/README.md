# ローカルHTTP・JSONラボ

外部ネットワークを使わず、JDK 21だけでHTTPの成功・404・タイムアウトを確認します。

ターミナル1:

```sh
javac --add-modules jdk.httpserver ApiServer.java ApiClient.java
java --add-modules jdk.httpserver ApiServer
```

表示されたポート番号を使い、ターミナル2で実行します。

```sh
java ApiClient http://localhost:8080
```

サーバーの起動引数にポートを渡せます。`java ApiServer 9090` のように指定します。
`/api/items/1` は200、存在しないIDは404、`/api/slow` はクライアント側で
タイムアウトします。終了はサーバー側でCtrl+Cです。
