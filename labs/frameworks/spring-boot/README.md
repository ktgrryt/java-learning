# Spring Boot greeting API

このラボは、Spring Bootを「アノテーションを写して起動する」だけで終わらせず、
Starter、自動構成、ControllerとService、外部設定、テスト、Actuator、成果物の関係を
小さなAPIで確認するためのものです。

教材画面の『目的ごとにテスト範囲を選ぶ』では`project-exercise`を複数ファイルで編集し、
`mvn test`で採点します。『Actuatorで運用し、安全に更新する』では`runtime-exercise`を動的ポートで起動し、API、入力不正、Actuator health、停止まで自動検証します。

## 到達目標

- `pom.xml`の各Starterが必要な理由を説明できる
- ControllerをHTTP境界、Serviceを業務処理として分けられる
- 設定をコードの再ビルドなしで上書きできる
- `@WebMvcTest`が確認する範囲を説明できる
- healthを確認し、管理endpointを無制限に公開しない
- 開発起動、テスト、JAR作成を自分で実行できる

## 1. ファイルとStarterを対応付ける

| ファイル・依存関係 | 役割 |
|---|---|
| `Application.java` | `@SpringBootApplication`からSpringと組み込みサーバーを起動する |
| `GreetingController.java` | URL、GET、query parameter、入力検証を扱う |
| `GreetingService.java` | 挨拶文を作る処理と外部設定を扱う |
| `application.properties` | 環境で変えられる設定とActuator公開範囲 |
| `spring-boot-starter-web` | Spring MVC、JSON変換、組み込みTomcatなど |
| `spring-boot-starter-validation` | `@NotBlank`、`@Size`による境界検証 |
| `spring-boot-starter-actuator` | healthなどの運用endpoint |

Starterは「機能を呼ぶアノテーション」ではなく、用途に必要な依存関係を安全な組み合わせで
追加する入口です。Spring Bootはクラスパスと設定を見てWebサーバーやJSON変換器などを
自動構成します。

## 2. 起動してHTTP境界を確認する

前提はJDK 21以降とMaven 3.9以降です。初回は依存関係をダウンロードします。

```bash
cd labs/frameworks/spring-boot
mvn spring-boot:run
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

`GreetingController`はHTTPと入力を扱い、正常時だけ`GreetingService`へ処理を渡します。
Serviceを分けた理由は、今後DBや業務ルールが増えてもControllerへ詰め込まないためです。

## 3. 外部設定を上書きする

`GreetingService`のprefixは`app.greeting.prefix`から読みます。Spring Bootのrelaxed bindingにより、
環境変数では大文字とunderscoreへ変換して上書きできます。

```bash
APP_GREETING_PREFIX=Welcome mvn spring-boot:run
curl 'http://localhost:8080/api/greeting?name=Aki'
# {"message":"Welcome, Aki"}
```

本番のpasswordやAPI keyは`application.properties`へコミットせず、Secret管理機構から渡します。
関連する独自設定が増えたら、`@Value`を増やすのではなく`@ConfigurationProperties`へまとめるのが
次の改善です。

## 4. Web層のテストを実行する

```bash
mvn test
```

`GreetingServiceTest`はSpringを起動せず、設定されたprefixと名前を組み立てる業務処理だけを高速に
確認します。一方、`GreetingControllerTest`の`@WebMvcTest`は、アプリ全体ではなくSpring MVC、
JSON変換、ValidationなどWeb層を中心に読みます。Serviceは`@Import`で本物の小さな実装を渡しています。
この2つを比べると、「Javaだけの単体テスト」と「SpringのWeb機能を含む部分テスト」の境界が分かります。
この規模ではMockitoを必須にせず、差し替える目的が明確になってからstub、fake、Mockitoを選びます。

追加で確認してみてください。

1. `name`未指定が400になるテストを足す
2. prefixが空文字のときに起動を失敗させる`@ConfigurationProperties`を検討する
3. エラーJSONを統一する`@RestControllerAdvice`を追加する

## 5. Actuatorを運用側から確認する

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

このラボは`health`だけをHTTP公開しています。`env`、`configprops`、`loggers`などを一括公開すると、
秘密情報の漏えいや動作変更につながります。必要なendpointだけを管理ネットワークと認証・認可で守ります。

## 6. 配布用JARを作る

```bash
mvn clean package
java -jar target/greeting-spring-boot-1.0.0.jar
```

組み込みTomcatを含む自己完結型アプリとして起動します。更新時はSpring BootのRelease Notesと
Migration Guideを確認し、設定変更、テスト、health、ロールバックを一組で実施してください。

## 完了チェック

- [ ] Starterを追加する前に用途を説明した
- [ ] 正常、空文字、21文字の応答を確認した
- [ ] 環境変数でprefixを変更した
- [ ] `mvn test`を実行した
- [ ] healthとreadinessを確認した
- [ ] JARから起動した

公式資料: [Auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)、
[Web](https://docs.spring.io/spring-boot/reference/web/index.html)、
[Testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)、
[Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)、
[Upgrading](https://docs.spring.io/spring-boot/upgrading.html)
