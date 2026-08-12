# 3製品の独立実践ラボと比較ラボ

同じAPIをSpring Boot、Open Liberty、Quarkusで動かします。各製品を初めて使う場合は、
先に製品別READMEを最後まで進めてください。その後、同じ契約をどう実現しているかを比較します。

## 製品別の入口

- [Spring Boot実践ラボ](spring-boot/README.md) — Starter、自動構成、組み込みサーバー、`@WebMvcTest`、Actuator
- [Open Liberty実践ラボ](open-liberty/README.md) — アプリケーションサーバー、Feature Manager、Jakarta EE/MicroProfile、Zero Migration、InstantOn
- [Quarkus実践ラボ](quarkus/README.md) — Extension、ビルド時最適化、Dev Services、継続テスト、JVM/Native、Update Tool

3製品の違いを一言で整理すると、Spring Bootは開発を始めやすくする自動構成と広いエコシステム、
Open Libertyは標準APIとversioned featureによる長期ランタイム保守、Quarkusはビルド時最適化と
cloud-nativeな開発・配備が中心です。優劣ではなく、解決したい問題の違いとして見ます。

## 必要なもの

- JDK 21以降
- Maven 3.9以降（Maven Wrapperのバイナリは同梱していません）
- 初回のみ、依存をダウンロードするためのネットワーク（数百MBになります）

依存関係やpluginの版を更新するときは、各製品のMigration Guideとテストを一組で扱ってください。

> 2026-08-12にJDK 21.0.5、Maven 3.9.16、macOS 15.6.1で、3製品の`package`と全ラボテストを実行済みです。
> さらに各成果物を起動し、共通API、空入力のHTTP 400、製品別readiness endpointを確認しています。

## 共通の契約

- `GET /api/greeting?name=Java`
- 成功: HTTP 200、`{"message":"Hello, Java"}`
- `name` は1〜20文字。空または長すぎる入力はHTTP 400
- readiness/health endpointを持つ

## 実行

各ディレクトリで開発モードを起動します。3つを同時起動する場合、Spring BootとQuarkusは既定で
8080を使うため、どちらかのportを変更してください。Open Libertyは9080です。

```bash
cd spring-boot
mvn spring-boot:run

cd ../open-liberty
mvn liberty:dev

cd ../quarkus
mvn quarkus:dev
```

## 成功したらこう出る

どの製品でも、契約どおりなら同じ応答になります。Open Libertyだけportを9080へ変えます。

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

成果物の形は異なります。Spring Bootは実行可能JAR、Open LibertyはWARとランタイム構成、
Quarkus JVM版は`target/quarkus-app`一式です。製品別READMEの手順でそれぞれ起動してください。

## 比べるところ

| 観点 | Spring Boot | Open Liberty | Quarkus |
|---|---|---|---|
| APIの中心 | Spring MVC | Jakarta REST | Jakarta REST |
| DI | Spring container | Jakarta CDI | ArC（CDI） |
| health | `/actuator/health` | `/health/ready` | `/q/health/ready` |
| 主な構成 | `application.properties` | `server.xml` | `application.properties` |
| 開発起動 | `spring-boot:run` | `liberty:dev` | `quarkus:dev` |
| 象徴的な仕組み | Auto Configuration | versioned feature / Zero Migration | Build-time Optimization |
| 主な成果物 | 組み込みserverを含むJAR | WAR + Open Liberty runtime | `quarkus-app`またはNative executable |
| 更新支援の考え方 | Migration Guide / Properties Migrator | 同じFeature版を保つ設計 | Update Tool / OpenRewrite + Migration Guide |

同じ契約でも、依存関係の組み立て、自動構成、サーバーFeature、ビルド時解析、成果物、更新方法が
異なります。コードの行数だけで比較しないでください。

測る場合は、同じJDK、CPU/memory limit、warm-up、request payload、計測時間にそろえます。
起動時間だけでなく、定常RSS、p95/p99 latency、throughput、build時間、障害時の調べやすさも記録し、
既存資産・標準準拠・組織supportを含めてADRに残してください。NativeとJVM、InstantOnと通常起動も、
異なる仕組みなので名前だけで同じ表へ並べず、採用予定の形を実際にbuildして測ります。

生成物（各ディレクトリの `target/`）はコミットしません。
