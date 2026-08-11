# 3フレームワーク比較ラボ

同じAPIをSpring Boot、Open Liberty、Quarkusで動かし、annotationの数ではなく、
build・設定・test・起動・health・成果物・運用の違いを観察します。

## 必要なもの

- JDK 21以降
- Maven 3.9以降（Maven Wrapperのバイナリは同梱していません）
- 初回のみ、依存をダウンロードするためのネットワーク（数百MBになります）

> Spring Boot 版は JDK 21.0.8 / Maven 3.9.12 / macOS 15 で動作確認。Open Liberty 版と Quarkus 版はこの環境で未確認です。

## 共通の契約

- `GET /api/greeting?name=Java`
- 成功: HTTP 200、`{"message":"Hello, Java"}`
- `name` は1〜20文字。空または長すぎる入力はHTTP 400
- readiness/health endpointを持つ

## 実行

各ディレクトリで開発モードで起動します。

```bash
cd spring-boot && mvn spring-boot:run
cd open-liberty && mvn liberty:dev
cd quarkus && mvn quarkus:dev
```

## 成功したらこう出る

どのフレームワークでも、契約どおりなら同じ応答になります（Spring Boot で実測）。

```bash
$ curl "http://localhost:8080/api/greeting?name=Aki"
{"message":"Hello, Aki"}

$ curl -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/greeting"
400
$ curl -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/greeting?name="
400
$ curl -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/greeting?name=aaaaaaaaaaaaaaaaaaaaa"
400
```

health endpointはフレームワークごとに違います。Spring Boot はこう返ります。

```bash
$ curl http://localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

`spring-boot:run` を使わず `mvn -DskipTests package` してから
`java -jar target/greeting-spring-boot-1.0.0.jar` でも同じ結果になります。

## 比べるところ

| 観点 | Spring Boot | Open Liberty | Quarkus |
|---|---|---|---|
| APIの中心 | Spring MVC | Jakarta REST | Jakarta REST |
| DI | Spring container | Jakarta CDI | ArC（CDI） |
| health | `/actuator/health` | `/health/ready` | `/q/health/ready` |
| 主な構成 | `application.properties` | `server.xml` | `application.properties` |
| 開発起動 | `spring-boot:run` | `liberty:dev` | `quarkus:dev` |

同じ契約が、設定ファイルの置き場所とhealthのパスだけ違う形で実現されていることを
確かめてください。

測る場合は、同じJDK、CPU/memory limit、warm-up、request payload、計測時間にそろえます。
起動時間だけでなく、定常RSS、p95/p99 latency、throughput、build時間、障害時の調べやすさも記録し、
既存資産・標準準拠・組織supportを含めてADRに残してください。

生成物（各ディレクトリの `target/`）はコミットしません。
