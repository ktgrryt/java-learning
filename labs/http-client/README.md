# ローカルHTTP・JSONラボ

外部ネットワークを使わず、JDK 21だけでHTTPの成功・404・タイムアウトを確認します。

## 必要なもの

- JDK 21以降のみ（`jdk.httpserver` はJDKに同梱）
- ターミナル2つ

> JDK 21.0.8 (IBM Semeru) / macOS 15 で動作確認

## 手順

ターミナル1でサーバーを起動します。

```sh
javac --add-modules jdk.httpserver ApiServer.java ApiClient.java
java --add-modules jdk.httpserver ApiServer
```

```
listening on http://localhost:8080
```

ポートを変えたいときは `java --add-modules jdk.httpserver ApiServer 9090` のように渡します。

表示されたポートを使い、ターミナル2でクライアントを実行します。

```sh
java ApiClient http://localhost:8080
```

## 成功したらこう出る

```
200 {"id":1,"name":"Java"}
404 {"error":"not found"}
TIMEOUT /api/slow
```

`/api/items/1` は200、存在しないIDは404、`/api/slow` はクライアント側のタイムアウトで
`TIMEOUT` になります。サーバーの終了はターミナル1で `Ctrl+C` です。

## 確認すること

1. `ApiClient` のタイムアウト値を `/api/slow` の遅延より長くすると、`TIMEOUT` が
   200に変わる。接続のタイムアウトと応答のタイムアウトは別物であることを確かめる
2. 404の本文もJSONで返っている。エラーでも契約どおりの形を返すこと
3. サーバーを止めたままクライアントを実行すると、404でもタイムアウトでもない
   **接続の失敗** になる。3種類を区別して扱う
