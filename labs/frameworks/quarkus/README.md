# Quarkus greeting API

このラボは、Quarkus RESTの書き方だけでなく、Extension、ビルド時最適化、dev mode、
Continuous Testing、Dev Services、JVM/Native配備、更新手順の関係を小さなAPIで確認します。

## 到達目標

- 要件から必要なQuarkus Extensionを選べる
- ビルド時設定と実行時設定を区別できる
- Quarkus REST、CDI、Validation、Config、Healthを使える
- dev mode、JUnit、`@QuarkusTest`、JVM packageを実行できる
- Dev Servicesを使える条件と本番設定との違いを説明できる
- JVMとNative Imageを測定結果から選べる
- Update ToolとSpring互換Extensionの限界を説明できる

## 1. ファイルとExtensionを対応付ける

| ファイル・Extension | 役割 |
|---|---|
| `GreetingResource.java` / `quarkus-rest-jackson` | URL、GET、JSON変換 |
| `GreetingService.java` | CDI BeanとMicroProfile Config |
| `quarkus-hibernate-validator` | `@NotBlank`、`@Size`による入力検証 |
| `GreetingReadiness.java` / `quarkus-smallrye-health` | readiness endpoint |
| `application.properties` | 実行時に変えられるアプリ設定 |

Extensionは依存関係を追加するだけでなく、対象ライブラリをQuarkusのaugmentation（ビルド時の
解析・生成処理）へ統合します。クラス探索やDI解析などを事前に行い、起動時の処理を減らすことが
Quarkusの中心的な設計です。

このラボは、公式が本番向けとして案内している3.33 LTS系列のメンテナンス版へ固定しています。
版を上げるときはMigration Guideとテストを一組で扱ってください。

## 2. dev modeとLive Reloadを使う

前提はJDK 21以降とMaven 3.9以降です。

```bash
cd labs/frameworks/quarkus
mvn quarkus:dev
```

別のターミナルから確認します。

```bash
curl 'http://localhost:8080/api/greeting?name=Java'
# {"message":"Hello, Java"}

curl -i 'http://localhost:8080/api/greeting?name='
# HTTP 400

curl -i 'http://localhost:8080/api/greeting?name=aaaaaaaaaaaaaaaaaaaaa'
# HTTP 400
```

`GreetingResource.java`のパスや`GreetingService.java`の処理を変更して再度curlすると、dev modeが
変更を検出して反映します。dev modeは開発用であり、そのまま本番プロセスとして使いません。

## 3. 実行時設定を変更する

`app.greeting.prefix`は実行時設定なので、再ビルドせず環境変数で上書きできます。

```bash
APP_GREETING_PREFIX=Welcome mvn quarkus:dev
curl 'http://localhost:8080/api/greeting?name=Aki'
# {"message":"Welcome, Aki"}
```

Quarkusの設定にはBuild time固定とRun time変更可能の区分があります。HTTP portや接続URLのような
環境値は実行時、Extensionやアプリ構造を決める設定はビルド時、という違いを各設定の公式資料で
確認します。ビルド時設定を配布後に環境変数だけで変えても反映されない場合があります。

## 4. JUnitとContinuous Testingを使い分ける

```bash
mvn test
```

- `GreetingServiceTest`: Quarkusを起動しない高速なJUnit
- `GreetingResourceTest`: `@QuarkusTest`でアプリを起動し、HTTP、JSON、Validation、CDIを確認

dev modeのターミナルで表示される操作案内から継続テストを再開すると、保存後に関係するテストが
実行されます。通常は`r`で再開できます。Live Reloadは変更反映、Continuous Testingは正しさの確認です。
テストデータは各テストが準備・後片付けし、実行順や固定sleepに依存させません。

### Java Caféの採点付き演習

`62-3`のproject問題では、`pom.xml`、Service、properties、`@QuarkusTest`を編集し、REST Jackson、
Hibernate Validator、SmallRye HealthのExtension、MicroProfile Config、実HTTP境界を`mvn test`で確認します。
学習者が変更できない受け入れテストも正常JSON、Validation 400、readinessを確認します。

`62-5`の必須runtime-labでは`mvn package`で`target/quarkus-app`一式を作り、採点側の動的portで
`quarkus-run.jar`をJVM起動します。REST、Validation、Health、停止まで実測し、Dockerは使いません。

Native Imageのcontainer buildは同じ`62-5`内の**任意発展問題**です。章クリアや★の分母には含まれず、
Docker daemonと公式UBI 9 builder/micro imageがある場合だけ実行します。Linux executableをbuildして
runtime containerへ格納し、Native REST、Health、cleanupを確認します。

## 5. Dev Servicesを試す（Docker/Podmanがある場合）

この最小ラボは外部DBを必要としないため、通常はコンテナを起動しません。仕組みを試す場合は作業用branchで
PostgreSQL Extensionを追加します。

```bash
mvn quarkus:add-extension -Dextensions=jdbc-postgresql
mvn quarkus:dev
```

dev/test用接続URLがなくDockerまたはPodmanを利用できると、Dev ServicesがPostgreSQLを起動して
接続設定を渡します。Docker/Podmanがない環境では起動できません。本番ではDev Servicesに任せず、
接続先、認証、backup、可用性を明示します。演習後は不要なExtensionを`pom.xml`から外してください。

## 6. HealthとJVM成果物を確認する

```bash
curl http://localhost:8080/q/health/live
curl http://localhost:8080/q/health/ready
curl http://localhost:8080/q/health/started

mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

`target/quarkus-app`ディレクトリ一式がJVM配布物です。`quarkus-run.jar`だけを取り出さないでください。
管理endpointは業務APIと公開範囲を分け、health詳細やメトリクスを必要な相手だけへ公開します。

## 7. Native Imageは測って選ぶ

DockerまたはPodmanがあれば、GraalVMをローカルへ導入せずコンテナbuildを試せます。

```bash
mvn package -Dnative -Dquarkus.native.container-build=true
# Podmanのとき
mvn package -Dnative -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman
```

`-Dnative` は `pom.xml` の `native` プロファイルを起こすための名前です。プロファイルが無いpomで
これを渡すと**mvnは成功したままJVMのjarだけを作り**、Native executableができません。
自分で作ったプロジェクトで無反応に見えるときは、まずプロファイルの有無を確かめてください。

Native Imageはcold startと初期メモリを小さくしやすい一方、build時間、動的Reflection、resource、
利用ライブラリ、障害調査に追加確認が必要です。Nativeを本番採用するならNative実行ファイルを対象にした
テストも追加し、JVM版と起動、RSS、定常性能、build時間を同じ条件で比較します。

採点付き任意演習の詳細と手動pullするimageは
[`native-exercise/README.md`](native-exercise/README.md)を参照してください。Quarkus 3.19以降の既定builderは
UBI 9系なので、最終runtime imageもUBI 9互換にそろえます。

## 8. 安全に更新する

```bash
# Quarkus CLIを導入している場合
quarkus update

git diff
mvn test
```

Update ToolはOpenRewriteで依存関係・コード・設定の一部を変換しますが、移行を全部自動化するものでは
ありません。Migration Guide、diff、JVMテスト、Native採用時のNativeテストを確認します。

Spring DI/Web/Data/Securityの一部には互換Extensionがありますが、完全なSpring Framework互換では
ありません。既存Springアプリを移す場合は利用APIを一覧化し、対応する部分、書き換える部分、廃止する
部分を小さな検証で分類します。新規開発ではQuarkus REST、CDIなどネイティブAPIも検討してください。

## 完了チェック

- [ ] 各Extensionの目的を説明した
- [ ] 正常、空文字、21文字の応答を確認した
- [ ] 環境変数でprefixを変更した
- [ ] JUnitと`@QuarkusTest`を実行した
- [ ] readinessとJVM成果物を確認した
- [ ] Dev Servicesがdev/test向けである理由を説明した
- [ ] JVM/Nativeの選定軸を説明した
- [ ] 更新後に確認する4項目を説明した

公式資料: [Get Started](https://quarkus.io/get-started/)、
[Dev Services](https://quarkus.io/guides/dev-services)、
[Continuous Testing](https://quarkus.io/guides/continuous-testing)、
[Native Image](https://quarkus.io/guides/building-native-image)、
[Update Tool](https://quarkus.io/guides/update-quarkus)、
[Springからの移行](https://quarkus.io/spring/migrate/)
