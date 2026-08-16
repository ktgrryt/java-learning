# Open Liberty greeting API

このラボは、Open LibertyのJavaコードだけでなく、アプリケーションサーバー、`server.xml`、
Feature Manager、Jakarta EE/MicroProfile、Zero Migrationの関係を小さなAPIで確認するものです。

## 到達目標

- Open LibertyランタイムとWARの役割を分けて説明できる
- Javaコードから必要なFeatureを見積もれる
- Jakarta REST、CDI、Validation、MicroProfile Config/Healthを使える
- `liberty:dev`、JUnit、WAR作成を自分で実行できる
- ランタイム更新とFeature版更新を別の変更として計画できる
- InstantOnが使える条件と通常起動との違いを説明できる

## 1. アプリとランタイム設定を対応付ける

| ファイル | 役割 | 関係するFeature |
|---|---|---|
| `RestApplication.java` | Jakarta RESTのベースパス | `restfulWS-4.0` |
| `GreetingResource.java` | URL、GET、query parameter、Validation | Jakarta REST、Validation、JSON-B |
| `GreetingService.java` | CDI Beanと外部設定 | CDI、`mpConfig-3.1` |
| `GreetingReadiness.java` | 受付可能状態を表す | `mpHealth-4.0` |
| `server.xml` | Feature、port、WARのcontext root | Feature Manager |

`pom.xml`の`provided`依存関係はコードをコンパイルするAPIです。実行時の実装はOpen Libertyが
提供します。その実装をどれだけ読み込むかを`server.xml`のFeature Managerで宣言します。

このラボは利用APIを確認し、次のversioned featureだけを有効にしています。

```xml
<featureManager>
    <feature>restfulWS-4.0</feature>
    <feature>cdi-4.1</feature>
    <feature>validation-3.1</feature>
    <feature>jsonb-3.0</feature>
    <feature>mpHealth-4.0</feature>
    <feature>mpConfig-3.1</feature>
</featureManager>
```

Platform Featureと個別Featureのどちらが常に正しいわけではありません。広くJakarta EE 11を使う
アプリは`<feature>jakartaee-11.0</feature>`の方が版をそろえやすく、機能が少ないサービスは個別指定の
方が役割を読みやすくなります。`validation-3.1`は、仕様名がJakarta Bean ValidationからJakarta
Validationへ変わったことに合わせたFeature名です。

## 2. dev modeで起動する

前提はJDK 21以降とMaven 3.9以降です。初回はOpen Libertyランタイムと依存関係をダウンロードします。

```bash
cd labs/frameworks/open-liberty
mvn liberty:dev
```

起動ログにサーバー準備完了とアプリ利用可能のメッセージが出たら、別のターミナルから確認します。
このラボだけHTTP portが`9080`である点に注意してください。

```bash
curl 'http://localhost:9080/api/greeting?name=Java'
# {"message":"Hello, Java"}

curl -i 'http://localhost:9080/api/greeting?name='
# HTTP 400

curl -i 'http://localhost:9080/api/greeting?name=aaaaaaaaaaaaaaaaaaaaa'
# HTTP 400
```

`GreetingResource`はHTTP境界、`GreetingService`は挨拶作成を担当します。`@ApplicationScoped`な
Serviceは共有されるため、リクエストごとのnameをフィールドへ保存しません。

## 3. 外部設定とHealthを確認する

MicroProfile Configは`META-INF/microprofile-config.properties`を読み、環境変数で上書きできます。

```bash
APP_GREETING_PREFIX=Welcome mvn liberty:dev
curl 'http://localhost:9080/api/greeting?name=Aki'
# {"message":"Welcome, Aki"}
```

```bash
curl http://localhost:9080/health/live
curl http://localhost:9080/health/ready
```

Readinessは「新しい通信を受けてよいか」、Livenessは「プロセスを再起動すべきか」です。
DBの一時障害をLivenessへ含めると再起動ループになり得るため、目的を分けます。

秘密情報はpropertiesへコミットせず、環境のSecret管理機構から渡してください。

## 4. テストする

```bash
mvn test
```

`GreetingServiceTest`はOpen Libertyを起動せず、純粋な業務処理と設定値の利用を確認します。
実務ではこれに加え、Failsafe PluginなどでLiberty起動後にHTTP経由の統合テストを行い、
Resource、Validation、JSON、Feature構成まで確認します。`liberty:dev`実行中はEnterでテスト、
`mvn liberty:dev -DhotTests`で変更時の自動テストも利用できます。

補足として、`@ApplicationScoped`のBeanはCDIが代理オブジェクト（proxy）を作るため、
`GreetingService`にはprivateではない引数なしconstructorを用意しています。文字列を受け取るconstructorは
JUnitから設定値を直接渡すための、この小さなラボ用の入口です。最初は「本番ではCDIが生成し、単体テストでは
普通のJavaオブジェクトとして生成できる」と理解すれば十分です。

### Java Café の採点付き演習

『Feature Managerで必要な機能を有効にする』では`server.xml`を直接編集し、XML構造とFeature構成を
検査します。『テスト・運用・InstantOnを使い分ける』のruntime-labでは、別の一時作業領域で次の流れを自動実行します。

1. `mvn test package`で実クラスを含む`greeting.war`を作成する。
2. 固定したOpen LibertyランタイムへWARを配備し、採点側が確保した動的portで起動する。
3. `CWWKF0012I`ログからREST、CDI、Validation、JSON-B、MicroProfile Health/Configの
   6 Featureが起動したことを確認する。
4. 正常REST、空白・21文字のValidation 400、readinessの`UP`をHTTPで確認する。
5. 検証後にserverとHTTP listenerが停止したことを確認する。

学習者が編集するのは`server.xml`、`GreetingResource.java`、`GreetingReadiness.java`です。
採点script、REST application、Service、設定、probeは固定されているため、一般Javaの文字列処理だけでは
合格できません。初回実行はランタイムと依存関係の取得に時間がかかります。

## 5. WARを作り、Zero Migrationの境界を確認する

```bash
mvn clean package
ls target/greeting.war
```

Zero Migrationが主に効くのは次の変更です。

```text
Open Libertyランタイム旧版 + 同じversioned feature
              ↓
Open Libertyランタイム新版 + 同じversioned feature
```

Feature版、Jakarta EE Platform版、Java版、サードパーティ依存を変更する場合は別の移行です。
ランタイムだけの更新でも回帰テストと段階リリースは行います。Zero Migrationは「テスト不要」ではなく、
ランタイム保守とAPI仕様移行を別の速度で進めやすくする設計です。

## 6. InstantOnは必要になってから試す

InstantOnは、LinuxのCRIUで起動途中のJVMをcheckpointし、コンテナ起動時にrestoreする方式です。
Native Imageとは異なりJVM/JITを保ったまま起動短縮を狙います。Linux、CRIU、対応JDK、対応Feature、
checkpoint前後の秘密情報・外部接続を確認する必要があります。まず通常起動を測り、cold startが
業務上の問題である場合だけ公式手順で検証してください。

## 完了チェック

- [ ] `server.xml`の各Featureをコード中のAPIへ対応付けた
- [ ] 正常、空文字、21文字の応答を確認した
- [ ] 環境変数でprefixを変更した
- [ ] `mvn test`とdev mode中のテストを実行した
- [ ] readinessとlivenessの意味を説明した
- [ ] WARを作成した
- [ ] ランタイム更新とFeature版更新を分けて説明した

公式資料: [Getting started](https://openliberty.io/guides/getting-started.html)、
[Feature overview](https://openliberty.io/docs/latest/reference/feature/feature-overview.html)、
[Feature Manager](https://openliberty.io/docs/latest/reference/config/featureManager.html)、
[Zero Migration Architecture](https://openliberty.io/docs/latest/zero-migration-architecture.html)、
[InstantOn](https://openliberty.io/docs/latest/instanton.html)
