# 3フレームワーク比較ラボ

同じAPIをSpring Boot、Open Liberty、Quarkusで動かし、annotationの数ではなく、
build・設定・test・起動・health・成果物・運用の違いを観察します。

## 共通の契約

- `GET /api/greeting?name=Java`
- 成功: HTTP 200、`{"message":"Hello, Java"}`
- `name` は1〜20文字。空または長すぎる入力はHTTP 400
- readiness/health endpointを持つ

## 実行

各directoryでMaven 3.9以降と、そのframework versionが対応するJDKを使います。

```bash
cd spring-boot && mvn spring-boot:run
cd open-liberty && mvn liberty:dev
cd quarkus && mvn quarkus:dev
```

このrepositoryにはMaven Wrapperのbinaryを含めていないため、Mavenが入っている環境では
`mvn ...` に読み替えられます。起動後は次を比較します。

| 観点 | Spring Boot | Open Liberty | Quarkus |
|---|---|---|---|
| APIの中心 | Spring MVC | Jakarta REST | Jakarta REST |
| DI | Spring container | Jakarta CDI | ArC（CDI） |
| health | `/actuator/health` | `/health/ready` | `/q/health/ready` |
| 主な構成 | `application.properties` | `server.xml` | `application.properties` |
| 開発起動 | `spring-boot:run` | `liberty:dev` | `quarkus:dev` |

測る場合は、同じJDK、CPU/memory limit、warm-up、request payload、計測時間にそろえます。
起動時間だけでなく、定常RSS、p95/p99 latency、throughput、build時間、障害時の調べやすさも記録し、
既存資産・標準準拠・組織supportを含めてADRに残してください。
