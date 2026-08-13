# Java Café 問題・学習目標整合性レビュー

- 初回レビュー日: 2026-08-13
- 現状反映日: 2026-08-13（Quarkus project / JVM runtime / optional Native接続後）
- 対象: 7編・56章・304 lessons・必須583 tasks + 任意発展1 task
- 観点: 「問題に正解できること」が、lessonの本題を理解・実践できることを意味するか
- 方針: 現在のJSON、採点条件、問題engine、labs、自動検証結果を読み取りで再確認

## 0. 現在の実装状況

初回レビュー後、問題形式を学習目標へ合わせるための基盤と、代表的なlabsの自動採点接続が実装された。

| type / 単元 | 現在数 | 用途 |
|---|---:|---|
| `single-file` | 571問 | Java文法、標準API、algorithm、純粋domain rule |
| `artifact` | 1問 | Open Libertyの`server.xml`を直接編集・構造検査 |
| `project` | 4問 | Spring Boot/Quarkus API、business app改修、logging障害調査を複数ファイルで実施 |
| `runtime-lab` | 必須7問 + 任意1問 | Spring Boot、Open Liberty、Quarkus JVM/Native、HTTP server、JFR、PostgreSQL、containerを実際に起動・観測・停止 |
| `preflight` | 8単元 | JDK、Maven、Docker、port等を★対象外で事前確認 |

現在接続されている非single-file問題は次のとおりである。

- `37-3`: 実行中JVMからJFRを記録し、`jfr summary`でイベントを確認
- `47-4`: localhostのHTTP serverを起動し、200・404・request timeoutを確認
- `54-2`: PostgreSQL 16 containerへmigrationを適用し、後方互換INSERT・UNIQUE・indexを確認
- `55-5`: Docker imageをbuildし、non-root・read-only・資源制限・readinessを確認
- `58-6`: 既存注文applicationを複数ファイルで改修し、11 testsを通す
- `59-6`: 実logから障害を調査し、安全な解析codeとincident reportを完成させる
- `60-5`: Spring BootのStarter、Controller、Service、設定、`@WebMvcTest`を複数ファイルで編集し、`mvn test`で確認
- `60-6`: Spring Boot JARを動的portで起動し、正常API・入力不正・Actuator health・停止を確認
- `61-2`: `server.xml`のFeature ManagerをXMLとして直接編集・検証
- `61-6`: 実WARをOpen Libertyへ配備し、6 Featureの起動ログ、REST、Validation 400、MicroProfile Health、停止を確認
- `62-3`: Quarkus Extension、Config、`@QuarkusTest`を複数ファイルで編集し、`mvn test`で実HTTP境界を確認
- `62-5`: `target/quarkus-app`をJVMモードで起動し、REST、Validation、SmallRye Health、停止を確認
- `62-5`任意発展: Mandrel container buildとUBI 9 runtime containerでNative REST/Healthを実測（章クリア・★対象外）

実行基盤は元labを一時copyへ隔離し、固定script、動的localhost port、一意なrun ID、timeout、
process tree停止、構造化check結果を扱う。Docker imageは自動pullせず、外部環境不足を学習者の
不正解として記録しない。全教材回帰検証はSpring Boot、Open Liberty、Quarkusのproject/runtimeを含む304 lessons・必須583 tasks＋任意発展1 task・2868 cases・373 quizzesで合格した。Docker daemon停止のため、任意のQuarkus Native Image、既存PostgreSQL、container runtime-labの3問は環境不足として実行を省略した。

ただし、これは初回レビューの問題を全面解消したことを意味しない。必須583問中571問は依然として
`single-file`であり、Jakarta EE 11、SQL基礎、JDK tool、CI/securityの
主要な実物課題は未接続である。以下では、解消済み・部分解消・未解消を区別して評価する。

## 1. 結論

ご指摘のOpen Liberty 61-1だけの問題ではない。同様の「説明は本題を扱っているが、主問題は一般的なJava処理へ置き換わっている」箇所が、特にJava実践・開発基盤編以降にまとまって存在する。

教材全体を次の4段階で評価した。

| 評価 | 意味 |
|---|---|
| A 直接整合 | 問題を解く過程で、lesson固有の構文・API・設計能力を直接使う |
| B 有効な模型 | 簡略化はあるが、本質的な状態変化・境界・失敗条件を実装させる。実物との差も明示される |
| C 関連するが不足 | 本題の一部は扱うが、暗記・単純判定・一般Java処理が中心で、実務能力の確認には不足する |
| D 再設計推奨 | 主問題へ合格しても、章題・lesson題の能力をほぼ証明できない |

初回は章単位でA 32章、B 11章、C 5章、D 8章だった。現在は保守的にA 32章、B 16章、C 4章、D 4章と評価する。性能測定章は実JFR labの追加でD→B、Spring Boot章はprojectとruntime-lab追加でD→B、Open Liberty章はXML artifactとruntime-lab追加でD→B、Quarkus章はprojectとJVM runtime-lab追加でD→B、deployment章は実container lab追加でC→Bへ改善した。Dは説明文が悪いという意味ではなく、主に評価方法の問題である。

現在の重要な判断は次の4点である。

1. **4種類の問題基盤は実装済みだが、必須教材移行は12問に留まる。** engine不足という構造的blockerは解消したため、今後は各章の到達目標に合わせて既存single-file問題を置換・補完する段階である。
2. **Spring Boot、Open Liberty、Quarkusは実製品を自動採点する段階へ進んだ。** QuarkusはExtension、Config、`@QuarkusTest`、JVM package、REST、Validation、Healthまで接続し、Native buildは任意発展へ分離した。Libertyの更新運用やQuarkusのDev Services/update比較等はまだPractice completeに達していない。
3. **性能、HTTP、実DB、container、capstone、loggingでは実物課題が星・合格・進捗へ接続された。** 一方、SQL基礎、JDK tool、Jakarta EE 11、CI/CD、TLS/OpenAPI/SBOM等は疑似問題だけでも章を修了できる。
4. **専門概念と無関係なsource checkはなお残る。** artifact/runtimeの受け入れ条件へ置き換えた箇所は改善したが、framework章の一般Java構文検査を段階的に減らす必要がある。

Criticalな技術誤りではない。問題engineの構造問題は大きく改善したが、「初心者が全発展章で実務レベルに達したと判断できる教材」という目標に対しては、未移行章がHighの課題として残る。

## 2. 良好な領域

次の領域は、説明と問題の学習目標がよく一致している。

- Java基礎編: 変数、演算、分岐、loop、配列、method、class、継承、polymorphism、interface、例外、String、collection、lambda、日時、ファイルI/Oを実際に書かせる。
- 標準Java実践: Stream、generics、BigDecimal、regex、sealed class、pattern matching、Clock等を直接使わせる。
- 並行処理: Thread、Atomic型、CountDownLatch、ExecutorService、CompletableFuture、Virtual Threadをコード上で使用する。
- Servlet/CDI/JPA等: Mini APIではあるが、annotation、lifecycle、request/session、DI、entity state等をコードとして体験させ、本物との差も比較的明示している。
- 総合演習: business appとloggingの既存labが`project`問題へ接続され、複数ファイルの変更、回帰test、SQL migration、PR/incident reportまで自動採点する。
- 実環境演習: HTTP、JFR、PostgreSQL、containerの4問題は、加工済み入力ではなく実process・network・DB・生成artifactを検証する。

これらは「説明を読んだ」だけでは通らず、対象概念をコードへ反映しなければ合格できない。今後の再設計では、この品質を発展章にも広げるべきである。

## 3. High: 教材全体に共通する構造問題

### H-01 問題engineの制約は解消、教材移行が未完了

- 重要度: High
- 分類: 構成 / 問題の不備 / 学習目標
- 対象: 主に第22章以降のtool、DB、framework、運用、security章
- 状態: **部分解消**。`artifact`、`project`、`runtime-lab`が実装され、server設定、複数file、process、network、DB、JFR、containerを直接採点できる。問題はengineではなく、必須583問中571問がまだsingle-fileである点へ移った。
- 代表例:
  - JDK toolを使うlessonで、目的語を`jshell`/`jdeps`等の名前へ変換する。
  - SQL lessonで、JavaのMapを使ってJOIN相当の集計を行う。
  - Spring Bootのsingle-file問題ではStarter名をMapから引く課題が残るが、60-5/60-6で実projectと実起動へ接続された。
  - Open Libertyの61-1等では製品理解を一般Javaの判定へ置き換えた問題が残るが、61-2/61-6で設定とruntimeの主要経路は実物へ接続された。
  - JFRは37-3に実記録labが追加されたが、同章のthread dump・JMHは加工済み入力のままである。
- 初心者への影響: 用語と正解文字列は覚えても、実ファイルの場所、commandの失敗、設定の相互作用、ログの読み方を経験しない。実務で最初に遭遇する「起動しない」「設定が効かない」「依存を解決できない」へ対処できない。
- 次の修正: 新しいengineを作るのではなく、未接続のlabsを既存4 typeへ移行する。Quarkus/Jakarta EEは`project`+`runtime-lab`、SQL/CI/configは`artifact`+実tool検証を使う。Springはauto-configuration診断やversion updateへ実践範囲を広げる。
- 修正理由: 評価環境を学習目標へ合わせる土台はできたため、章ごとのacceptance criteriaを実物へ移すことが次の律速になる。

### H-02 一部labsは修了条件へ接続、章単位のPractice completeは未実装

- 重要度: High
- 分類: 構成 / 学習目標
- 対象: `labs/`全体と進捗管理
- 状態: **部分解消**。diagnostics、HTTP、実DB、container、Spring Boot、business app、loggingのlabsは正式問題となり、成功時に通常の問題と同じく星・進捗へ記録される。
- 未解消例: `labs/testing-maven`、`labs/jdk-tools`、`labs/sql`、`labs/jakarta-ee11`はまだ章の必須実践へ接続されていない。Open LibertyとQuarkusは通常起動の主要経路を接続済みだが、Liberty更新運用・InstantOn、Quarkus Dev Services・updateは未接続である。
- 初心者への影響: 学習者は自然に「章をクリアした＝製品を使える」と解釈する。実際にはcommandを一度も実行していない可能性がある。
- 修正案: 現在の星接続を広げつつ、各章の進捗を「概念」「coding」「practice」の3層で表示する。製品名を冠する章ではPractice completeを章の実務修了badgeへ必須化する。
- 修正理由: 簡略問題自体は導入として有用だが、それを最終評価にしないことで価値を保てる。

### H-03 source checkがdomain能力ではなく一般Javaの字面を測る

- 重要度: High
- 分類: 問題の不備
- 状態: **部分解消**。artifact/project/runtime-labではXML、Spring annotation・Starter・test report、HTTP、JFR、DB、containerのdomain結果を検査できるようになった。ただし発展章の既存single-file source checks、特に`ch60`〜`ch62`には一般Javaの字面を測るものが残る。
- 問題点: `switch`、`LinkedHashSet`、`getOrDefault`、`service.message`等を必須にするが、それらはSpring/Liberty/Quarkusの能力ではない。
- 初心者への影響: frameworkを理解していなくても指定構文で合格できる一方、正しい別実装は不正解になり得る。
- 修正案: `pom.xml`のdependency、annotation、config key、起動結果、HTTP response、test report等、domain artifactを検査する。構文指定はその構文自体がlesson目標の場合だけにする。
- 修正理由: 採点条件はlessonの到達目標をそのまま表す必要がある。

### H-04 「知識確認」と「実務能力確認」が同じ星で扱われる

- 重要度: High
- 分類: 構成 / 学習目標
- 対象: 発展編全体
- 状態: **未解消**。preflightは★対象外として分離されたが、簡略simulationと本物のproject/runtime-labは同じ星で、Concept/Coding/Practiceの区別はUI・進捗modelにまだない。
- 初心者への影響: 何を説明でき、何を実行でき、何を診断できるのかが本人にも採用側にも分からない。
- 修正案: 各lessonを「説明できる」「実装できる」「壊れた状態を直せる」「判断理由を説明できる」のrubricで評価する。
- 修正理由: 実務レベルはAPI暗記ではなく、実装・診断・判断を組み合わせた能力である。

## 4. 最優先で再設計する4章と、改善した4章

初回D評価8章のうち、性能測定は実JFR runtime-labによりB、Spring Bootはproject＋runtime-labによりB、Open Libertyは`server.xml` artifact＋runtime-labによりB、Quarkusはproject＋JVM runtime-labによりBへ改善した。残るD評価4章は、実行基盤不足ではなく教材接続の問題として扱う。

### D-01 `ch50-java-history-platform.json` Javaの成り立ちとプラットフォーム

- 状態: **未解消（D）**

- 対象lesson: 50-1、50-2、50-4、50-5を中心に全章
- 現在の問題: 年表sorting、カテゴリ件数、識別子prefix分類。
- ずれ: 説明は「年号暗記ではなく、JVM・bytecode・互換性が生まれた理由を理解する」と宣言するが、問題は年・名称・カテゴリの処理を要求する。主にComparator、Map、ifの練習になっている。
- 改善:
  - 50-1はcode問題ではなく、source→bytecode→OS別JVMの経路を組み立てるscenario問題へ変更。
  - 同じclass fileを異なるruntime条件で動かす最小labを追加。
  - JEP/JSR/OpenJDK/TCKはprefix判定ではなく、記事の主張を読み「提案・仕様・実装・適合検査」のどの証拠か判断させる。
  - 歴史年表はquizまたは任意読み物へ下げる。

### D-02 `ch46-sql-database.json` SQLとリレーショナルデータベース

- 状態: **未解消（D）**。`54-2`には実PostgreSQL migrationが追加されたが、SQL基礎章`46-1`〜`46-5`自体は7 tasksすべてsingle-fileのままである。

- 対象lesson: 46-1〜46-5
- 現在の問題: Javaでconstraint、JOIN、実行計画の閾値、lock順を模擬する。
- 確認結果: 7 tasks中、実際のSQL文、JDBC、DB processを用いるtaskは0件。
- ずれ: 「SQLを学ぶ」章でSQLを書かずに合格できる。特に46-2はMap集計でLEFT JOIN相当を作るため、SQLのjoin条件、NULL、GROUP BY、HAVINGを実践できない。46-3もEXPLAINを読まず`rowsRead/rowsReturned`の整数を比較する。
- 改善:
  - schemaへPRIMARY KEY、FOREIGN KEY、NOT NULL、CHECKを追加し、失敗testを通す。
  - SELECT/LEFT JOIN/GROUP BY/HAVINGを直接記述して期待結果と比較する。
  - PostgreSQL/H2のEXPLAIN出力からscan/index、推定行数、実測行数を読ませる。
  - migrationを適用し、旧・新app双方の契約testを通す。
- 実務修了条件: `labs/integration-data`のmigration接続は完了。次は`labs/sql`でDDL、JOIN、集約、EXPLAINを直接採点する。

### D-03 `ch52-team-delivery.json` チーム開発・ビルド・品質管理

- 状態: **未解消（D）**

- 対象lesson: 52-1〜52-6
- 現在の問題: PRのfile/line閾値、依存pair、test時間の足し算、`n*n`、4個のbooleanでrelease判定。
- ずれ: Git diff、merge conflict、Maven/Gradle、test report、CI YAML、dependency scanを一度も扱わない。任意の数値ルールを実装するJava問題になっている。
- 改善:
  - 実際のdiffから変更目的外のfileやsecretを見つけるreview課題。
  - 壊れた`pom.xml`/`build.gradle`のdependency convergenceを直す。
  - failing CI logから最初の根本原因を特定する。
  - `.github/workflows/*.yml`へJDK、cache、test、scan、artifact uploadを追加する。
  - feature flagを用いて小さくmergeするproject演習。

### 改善-01 `ch37-performance-lab.json` 性能測定とJVM計測ラボ

- 状態: **D→Bへ改善**
- 実装済み: `37-3`にruntime-labを追加し、`AllocationDemo`を実JVMで起動、JFR fileを生成し、`jfr summary`でイベントを読み取る。模範解答は`settings=profile`と短い記録時間を選ばなければ合格しない。
- 残る問題: 37-1、37-2、37-4、章末の多くは加工済みデータの集計である。thread dump、lock owner cycle、GC log、JMH projectはまだ直接扱わない。
- 改善:
  - 意図的に競合・deadlockするprocessを起動し、`jcmd Thread.print -l`を取得する。
  - 2時点のdumpから固定したstack/lock ownerを特定する。
  - JFR file生成とsummaryは完了。次は`jcmd JFR.start`や`jfr print`からallocation/lock/GC eventを抽出する。
  - 小さなJMH benchmarkの誤り（dead code elimination、warmup不足）を直す。
- 既存資産: `labs/diagnostics`は正式問題へ接続済み。DeadlockDemoとGC logを追加のruntime checkへ接続する。

### D-05 `ch48-jakarta-ee11.json` Jakarta EE 11アップデート

- 状態: **未解消（D）**。`48-0`のJDK/Maven/port事前確認は追加されたが、事前確認は環境条件の検査であり、Jakarta EE 11能力の採点ではない。

- 対象lesson: 48-1〜48-5
- 現在の問題: version整数比較、memory repository検索、日時変換、log redaction。
- ずれ:
  - 48-1はPlatform/Web/Core Profileの中身を問わず、Java/Jakartaの整数だけを見る。
  - 48-2はJakarta Dataを題名にするが、repository interface越しのList検索で、Jakarta Data annotation・query derivation・runtime implementationを使わない。
  - 48-3、48-4は内容自体は有用だが、Jakarta EE 11固有のupdate能力をほとんど測らない。
- 改善:
  - 用途別にCore/Web/Platformを選び、必要APIとの差分と配備先対応を検証する。
  - Jakarta Data repositoryを実際に宣言し、container上でCRUD/page/sort testを実行する。
  - EE 10→11のbuild/server migrationで失敗するprojectを修正する。
  - `labs/jakarta-ee11`の配備・HTTP結果を必須化する。

### 改善-03 `ch60-spring-boot.json` Spring Boot実践入門

- 状態: **D→Bへ改善**。`60-0`のJDK/Maven/port事前確認に加え、`60-5`へproject問題、`60-6`へruntime-labを接続した。
- 実装済み:
  - `pom.xml`、Controller、Service、`application.properties`、Controller test、Service testの6ファイルを編集する。
  - Web、Validation、Actuator、test Starter、constructor injection、`@NotBlank`/`@Size`、外部設定を参照専用テストで検証する。
  - `@WebMvcTest`と`MockMvc`で正常・空白・21文字のHTTP境界を確認し、ServiceはSpring非起動のJUnitで確認する。
  - `mvn test`成功後にJARを採点側が確保した動的portで起動し、正常API、入力不正400、Actuator healthの`UP`を実HTTP検証する。
  - `EXIT`/`INT`/`TERM` trapと明示停止で、検証後にserver processを残さない。停止自体も構造化checkとして採点する。
- 検証結果: project参照解は10 tests成功。runtime参照解は`spring-tests`、`spring-api`、`spring-validation`、`spring-health`、`spring-stop`の5 checksが成功し、Java Café API経由の60-5/60-6限定回帰も全16 casesで合格した。さらにDI constructor、`@Service`、`@Value`、Actuator classpath、properties実値を受け入れテストで固定した。
- 残る問題:
  - 既存single-file問題は導入模型として残り、auto-configurationのCondition Evaluation Reportやback-offを実際には観測しない。
  - `@ConfigurationProperties`の型付き設定・起動時validation、例外handler、実DB、security、version updateは未採点である。
  - runtime-labはhealthだけを公開するが、`env`等が非公開であることを独立HTTP checkにはしていない。
- 次の改善:
  - user bean追加前後のCondition Evaluation Reportでauto-configuration back-offを診断する。
  - `@ConfigurationProperties`とvalidationで設定不足時の起動失敗を確認する。
  - migration guideに基づくversion updateとrollback課題を用意する。

### 改善-02 `ch61-open-liberty.json` Open Liberty実践入門

- 状態: **D→Bへ改善**
- 実装済み:
  - `61-0`でJDK/Maven/9080/9443を事前確認し、`61-2`では`server.xml`をartifactとして直接編集する。XML整形式とXPathでREST/CDI/JSON-B/Health Feature、HTTP endpoint、重複を検査する。
  - `61-6`では`server.xml`、Jakarta REST Resource、MicroProfile Readinessの3ファイルを編集する。採点時に`mvn test package`を実行し、Resource classを含む実`greeting.war`を検査する。
  - Open Liberty 26.0.0.8を一時領域へ準備し、採点側が確保した動的portへWARを配備する。`CWWKF0012I`起動ログからREST、CDI、Validation、JSON-B、MicroProfile Health/Configの6 Featureを確認する。
  - 実HTTPで正常RESTのJSON、空白と21文字のValidation 400、`/health/ready`の`UP`とcheck名を検証する。終了時は明示停止とtrapを使い、HTTP listenerが応答しないことも採点する。
  - Oracle JDK 21でarchive WARのCDI proxy生成に必要だったmodule openは固定`jvm.options`へ閉じ込め、学習者の本題をJDK module調査へ逸らさない。
- 検証結果: 参照解の直接実行で`liberty-war`、`liberty-features`、`liberty-rest`、`liberty-validation`、`liberty-health`、`liberty-stop`の6 checksが成功した。Java Caféの61-6限定回帰もstarterの意図した失敗と参照解を含む全11 casesで合格した。
- 残るずれ:
  - 61-1は説明がアプリケーションサーバーの本質へ改善された一方、主問題はMaven commandと一般Java判定である。
  - 61-3単体の主問題はJakarta REST/CDI/Validationのannotationを使わず一般Javaでpathを連結するが、61-6では実annotationとHTTP境界を扱う。
  - 61-4以降の既存single-file問題は用語分類が中心で、Configの環境変数上書きやHealthの障害遷移はまだ実測しない。
  - Zero Migrationのruntime更新比較とInstantOnのcheckpoint/restoreは未採点である。
- 改善:
  - WAR作成、Featureログ、Jakarta REST/Validation、MicroProfile Health、停止の通常起動経路は完了。次はConfigの環境値上書きとreadiness DOWN→UP遷移を独立checkにする。
  - WARとserver configの変更を分け、port変更だけならWARを再buildしなくてよいことを観測させる。
  - runtime updateとfeature updateを別branchで行い、regression test差を確認する。
  - InstantOnは対応環境でのみ発展labとし、通常JVMとの実測比較を要求する。

### 改善-04 `ch62-quarkus.json` Quarkus実践入門

- 状態: **D→Bへ改善**。`62-0`のJDK/Maven/portと任意Docker事前確認に加え、`62-3`へproject問題、`62-5`へ必須JVM runtime-labと任意Native runtime-labを接続した。
- 実装済み:
  - `pom.xml`へREST Jackson、Hibernate Validator、SmallRye Health Extensionを追加し、BOM管理の依存関係として`mvn test`する。
  - `GreetingService`へMicroProfile `@ConfigProperty`をconstructor injectionし、`application.properties`の値を実HTTP JSONへ反映する。
  - `GreetingResourceTest`を`@QuarkusTest`にし、REST Assuredで正常API、Validation 400、readinessを確認する。変更不能な受け入れテストが同じ境界を独立検証する。
  - `mvn package`で`target/quarkus-app`一式を作り、採点側の動的portで`quarkus-run.jar`をJVM起動する。正常JSON、空白・21文字の400、SmallRye Healthの`greeting=UP`、process/listener停止を実測する。
  - Native Imageは`required: false`の任意発展へ分離した。必要なDocker imageを自動pullせず、Mandrel UBI 9 builderでLinux executableを作り、UBI 9 micro image内でREST/Health/cleanupを確認する。最大10分を許すが章クリア・★・カフェ報酬・復習の分母に含めない。
- 検証結果: project参照解は固定受け入れテストを含む8 testsに合格。JVM参照解は`quarkus-tests`、`quarkus-jvm-package`、`quarkus-api`、`quarkus-validation`、`quarkus-health`、`quarkus-stop`の6 checksに合格した。Java Café経由の62-3/62-5限定回帰も合格し、Docker停止中のNativeだけ環境不足として省略した。
- 残る問題:
  - 既存single-file問題はExtension名、build/run-time分類、Dev Services条件等の導入模型として残る。
  - build-time固定設定をpackage後に変更した場合の警告・非反映をまだ観測しない。
  - Dev Servicesで実DBを起動する演習、Continuous Testing、Update Tool/Migration Guide差分は未採点である。
  - Native発展は実buildを設計したが、現在環境ではDocker daemon停止のため完走結果は要確認である。
- 次の改善:
  - build-time固定設定とrun-time設定をpackage後に変更し、反映差をlogとHTTPで確認する。
  - container runtimeあり/なしでDev Servicesの起動差を確認し、本番の明示DB設定と分離する。
  - Update Toolのdiff、Migration Guide、JVM回帰を組み合わせた更新課題を追加する。
  - Native対応環境でJVM/nativeを同じtest suiteと負荷条件で検証し、起動時間・RSS・build時間をADRへ記録する。

## 5. C評価: 本題には関連するが、実務到達には不足する4章

### C-01 `ch51-jdk-version-tooling.json`

- 良い点: `--release`、source/class/runtimeの区別、LTSを運用条件として扱う説明は良い。
- 弱い点: 51-3はtool名の対応表、51-4は状態語の分類であり、toolを使わない。章末も候補sortingが中心。
- 改善: `javac --release`の成功/失敗、`javap -verbose`のmajor version、`jdeps`のmodule、`jlink` imageを実際に作る。`labs/jdk-tools`を必須化する。

### C-02 `ch30-jvm-memory.json`

- 良い点: class loaderを`Class.forName`等で直接観察する問題は整合する。
- 弱い点: 30-2「heap・stack・GCとmemory leak」の主問題はLRU cacheとbounded StringBuilderであり、heap、GC、retained referenceを観測しない。
- 改善: 有界/無界cacheを実行し、heap推移、class histogram、GC logを比較する。stack overflow、OOM、GC pressureを安全な小規模processで再現する。

### C-03 `ch53-framework-options.json`

- 良い点: 必須条件、既存資産、運用性、performanceを同条件で比較しADRへ残す説明は実務的。
- 改善済み: `53-0`でJDK/Maven、3製品のport、任意Dockerを事前確認できる。比較前の環境差を本題から分離した。
- 弱い点: 53-1/53-6は任意scoreの最大値、53-2は名前長、53-3/53-5は製品特徴のboolean分類である。候補の事実を測らず、入力済み点数を足すだけである。
- 改善: 3つの同一API labからbuild時間、起動時間、RSS、test時間、artifact構造を収集し、hard constraintを先に適用してADRを作る。数値score問題は導入に留める。

### C-04 `ch61-open-liberty.json`

- 良い点: アプリケーションサーバーの責務、Jakarta EE/MicroProfile、Feature Manager、runtime更新と仕様更新の区別が明確になった。`61-2`では`server.xml`を直接編集する。
- 弱い点: Liberty runtimeを起動していないため、feature不足の起動log、WAR配備、Jakarta REST/CDI/Validation、MicroProfile Config/HealthのHTTP動作をまだ証明しない。
- 改善: `labs/frameworks/open-liberty`をruntime-labへ接続し、設定修復→起動→test→HTTP→停止を採点する。

### C-05 `ch56-security-api.json`

- 良い点: JWTのissuer/audience/time/scopeと対象単位認可は、有効なapplication-level問題である。
- 弱い点: TLS lessonが残り日数計算だけ、OpenAPI/SBOM lessonが変更名分類だけで、chain/SAN/truststore/contract diff/supply-chain artifactを扱わない。
- 改善: `keytool`/`openssl`の出力読解、wrong SAN/expired/untrusted CAの拒否、OpenAPI diff、SBOM生成・脆弱性scan結果のtriageを必須にする。

## 6. B評価の中で個別修正したいlesson

以下は章全体を作り直す必要はないが、特定lessonの主問題を置き換えるとよい。

| 重要度 | lesson | 現在の問題 | 問題点 | 推奨置換 |
|---|---|---|---|---|
| Medium | 21-5 Mavenとproject | version文字列比較・競合判定 | `pom.xml`、scope、build lifecycleを扱わない | 壊れたpomの修正、`mvn test/package/dependency:tree` |
| Medium | 45-4 Mavenと成果物 | dependency文字列の重複判定 | Mavenの実際の解決規則やartifactを確認しない | dependency treeとJAR内容の検査 |
| Medium | 45-5 中間演習 | 自作mini test runner | JUnitを学んだ直後にrunner実装へ戻る | failing JUnit testの追加・修正を主問題にする |
| Low | 47-2 HttpClient | requestを組み立てるが通信しない | 単独lessonでは通信しない | **章内で部分解消済み:** 47-4 runtime-labでlocal serverへ200・404・timeoutを実通信する |
| Low | 54-2 schema migration | 1問目は`expand`等を固定文字列へ変換 | 導入問題だけでは実migrationを設計しない | **解消済み:** 同lessonの2問目でPostgreSQLへV1〜V3を適用し、旧INSERT・UNIQUE・indexを確認する |
| Medium | 54-1 実DB検証 | connection budgetの割り算 | lessonのDB製品差、migration、commit behaviorを測らない | PostgreSQL/H2差を再現するtest |
| Medium | 38-2 可観測性 | status/latency集計 | trace/log correlationが題名に対して弱い | trace IDでmetric→trace→logを辿る演習 |
| Low | 58-5 PRで引き継ぐ | 5個のboolean checklist | 単独問題では実PR evidenceを作らない | **章内で解消済み:** 58-6 projectで`PR.md`を編集し、実装・migrationと同じacceptance testsで検証する |

### Bへ改善した`ch55-deployment-observability.json`

- `55-0`でJDKとDocker daemonを事前確認する。
- `55-5`にcontainer runtime-labが追加され、Dockerfileからimageをbuildし、non-root USER、read-only filesystem、memory/CPU limit、実HTTP readinessを検証する。
- 残る課題はSIGTERM後のdrainingを外部から観測すること、設定注入、OpenTelemetry/metrics/traceの実出力である。章全体はCからBへ改善したが、Practice completeには追加演習が必要である。

## 7. 56章の整合性評価

### Java基礎編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 1 はじめてのJava | A | 出力を直接書く |
| 2 変数と型 | A | 型・変数・finalを直接使用 |
| 3 計算と入力 | A | 演算・Scanner・castを直接使用 |
| 4 条件分岐 | A | 条件、equals、switchを直接使用 |
| 5 繰り返し | A | 各loop、break、continueを直接使用 |
| 6 配列 | A | 配列、添字、走査、2次元配列を直接使用 |
| 7 method | A | 引数、戻り値、値渡しを直接実装 |
| 8 class | A | class、constructor、staticを直接実装 |
| 9 encapsulation | A | private、immutable classを直接実装 |
| 10 inheritance | A | extends、override、superを直接実装 |
| 11 polymorphism | A | 抽象classとdynamic dispatchを直接使用 |
| 12 interface | A | interfaceと差し替えを直接実装 |
| 13 exception | A | throw/catch/resource管理を直接実装 |
| 14 String | A | String APIとStringBuilderを直接使用 |
| 15 wrapper | A | boxing、parse、nullを直接扱う |
| 16 collection | A | List/Set/Mapを用途別に直接使用 |
| 17 lambda | A | functional interfaceとlambdaを直接使用 |
| 18 modern Java | A | var、record、switch式等を直接使用 |
| 19 date/time | A | java.timeを直接使用 |
| 20 file I/O | A | Files/Path/charset/resourceを直接使用 |
| 21 総仕上げ | A | 基礎概念を組み合わせる |

### Java実践・開発基盤編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 22 Javaの成り立ち | D | 歴史・platform理解をsorting/countingへ置換 |
| 23 JDK選定・tool | C | 説明は良いがtoolを使わず名称分類が中心 |
| 24 Stream/Optional | A | Stream pipelineを直接実装 |
| 25 generics/collection設計 | A | wildcard、erasure、queue等を直接実装 |
| 26 数値/text | A | BigDecimal、BigInteger、regex、localeを直接使用 |
| 27 型pattern/metadata | A | sealed、pattern、reflectionを直接使用 |
| 28 実務date/time | A | Instant/Zone/DST/Clockを直接使用 |
| 29 test/build | B | JUnit部分は良いがMaven部分が疑似問題 |
| 30 SQL/RDB | D | SQLを書かずJavaでDB概念を模擬 |
| 31 JSON/HTTP | B | HttpClientに加えlocal serverへの実通信・200/404/timeoutをruntime-labで確認。JSON libraryは未導入 |
| 32 team delivery | D | Git/build/CIを使わず数値・boolean判定 |

### JVM・並行処理・性能編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 33 JVM memory | C | class loadingは直接、heap/GCは観測しない |
| 34 thread safety | A | 実thread・atomic・lockを直接使用 |
| 35 task/async/I/O | A | Executor、Future、Virtual Thread、NIOを直接使用 |
| 36 performance lab | B | 実JVMからJFRを生成・summary確認。thread dump、GC log、JMHは未接続 |

### Web・Jakarta EE編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 37 業務app骨格 | B | layer/DTO/exceptionは直接。Maven lessonのみ弱い |
| 38 Servlet/HTTP | A | Mini Servlet APIでrequest/responseを直接実装 |
| 39 Web state | A | request/session/cookie/filterを直接実装 |
| 40 JDBC | B | Mini DBだがSQL/JDBC lifecycleを直接扱う |
| 41 CDI | B | Mini DIだがscope/qualifier/eventを直接扱う |
| 42 JPA | B | Mini JPAだがentity/state/JPQLを直接扱う |
| 43 transaction | B | annotationと状態模型は有効。実DB transactionが必要 |
| 44 REST/security | B | resource/status/authzを直接扱う |
| 45 Validation/production | B | Validation境界は直接。運用部分は簡略 |
| 46 Jakarta EE 11 | D | EE11固有機能を一般Javaへ置換 |

### 業務framework編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 47 framework選定 | C | 判断軸は良いが架空score計算が中心 |
| 48 Spring Boot | B | Starter・DI・Validation・設定・@WebMvcTestをprojectで編集し、実JARのAPI・400・health・停止をruntime-labで確認。自動構成診断と更新演習は未接続 |
| 49 Open Liberty | B | server.xmlを直接編集し、実WAR・6 Feature起動ログ・REST・Validation 400・MicroProfile Health・停止をruntime-labで確認。更新運用とInstantOnは未接続 |
| 50 Quarkus | B | Extension・Config・@QuarkusTestをprojectで編集し、JVM package・REST・Validation・Health・停止をruntime-labで確認。Native実buildは任意発展。Dev Services/updateは未接続 |

### 本番運用・security編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 51 実DB/messaging/batch | B | PostgreSQL migrationを実containerで検証。message brokerと実batch runtimeは未接続 |
| 52 resilience/observability | B | retry等の核心は実装するが実library・telemetryは不足 |
| 53 deployment/observability | B | 実containerをnon-root・read-only・資源制限で起動しreadiness確認。telemetryは未接続 |
| 54 security/API契約 | C | JWT/authzは良い。TLS/OpenAPI/SBOMが浅い |

### 総合演習編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 55 business app capstone | A | project問題で既存code、SQL migration、PR、11 testsを統合 |
| 56 logging/incident | A | project問題で実log、sanitize、timeline、incident report、11 testsを統合 |

## 8. 最高品質を目指す評価設計

### 8.1 lessonを「知る→使う→壊す→直す→説明する」で構成する

例としてSpring BootのAuto Configurationなら、次の5段階を1セットにする。

1. 知る: classpathとconditional configurationの説明・quiz。
2. 使う: Starterを追加してappを起動する。
3. 壊す: dependencyまたはpropertyを外し、起動failure/reportを観察する。
4. 直す: user beanを追加してback-offさせ、testを通す。
5. 説明する: 「何が自動で、何を自分が決めたか」を短い回答またはADRへ記録する。

この形なら、名称暗記、成功例の写経、偶然動いた状態のいずれも単独では合格できない。

### 8.2 問題typeを4種類へ分ける — 基盤実装済み

| type | 適する内容 | 採点対象 |
|---|---|---|
| `single-file` | 文法、標準API、algorithm、純粋domain rule | compile、test、source check、hidden cases |
| `project` | layer、DI、framework、test、複数file変更 | 隔離copy内の固定build/test script |
| `artifact` | pom、server.xml、properties、SQL、Dockerfile、OpenAPI、CI YAML | XPath、JSON Pointer、property、regex等の構造検査 |
| `runtime-lab` | JVM tool、DB、HTTP、container、security、observability | command exit、生成artifact、HTTP response、構造化runtime check |

4 typeは実装済みである。加えて、環境条件だけを★から分離する`preflight` lessonも実装された。
次の課題はtypeの追加ではなく、各章の問題を適切なtypeへ移すことである。

### 8.3 修了条件を3層にする

状態: **未実装**。preflightは★対象外、runtime-labは通常問題として進捗接続されたが、
Concept/Coding/Practiceを別々に表示・判定するmodelはまだない。

- Concept complete: quizと説明問題に合格。
- Coding complete: kata/project testに合格。
- Practice complete: labを実行し、観測結果と修正を提出。

「Java基礎修了」はCoding completeまででもよいが、「Spring Boot実践」「Open Liberty実践」「性能診断」「本番security」等はPractice completeを必須にする。

### 8.4 実務rubric

各章の終了時に次を0〜2点で評価する。

1. 対象の役割を、自分の言葉で説明できる。
2. 最小の正常系を実装・起動できる。
3. 代表的な失敗を再現し、error/logから原因を特定できる。
4. testで修正を固定できる。
5. 制約とtrade-offを説明し、選択理由を記録できる。

合計8/10以上かつ「実装」「診断」が各1点以上を実務修了の最低条件とする案が妥当である。

## 9. 改訂優先順位

### Phase 1: 誤った達成感を防ぐ

1. **部分完了:** preflightを「準備・★対象外」として分離表示した。Practice completeの別表示は未実装。
2. **部分完了:** Spring Boot、Open Liberty、Quarkusはproject/artifact/runtime問題を通常進捗へ接続した。Native Imageは任意発展として通常進捗から分離した。
3. **部分完了:** 37-3、47-4、54-2、55-5、60-5、60-6、61-2、61-6へ実物問題を追加した。50-1、51-3、46-2、37-2、62-1等は未置換。
4. **未完了:** domainと無関係なsource checkを削除し、artifact/runtime acceptance criteriaへ移す。

### Phase 2: 既存labsを正式な問題へ昇格

1. **部分完了:** `labs/diagnostics`、`labs/http-client`、`labs/integration-data`、`labs/delivery`、`labs/frameworks/spring-boot`、`labs/business-app-capstone`、`labs/logging-investigation`を自動採点へ接続した。
2. **部分完了:** Open LibertyとQuarkus runtimeは正式問題へ接続した。`labs/testing-maven`、`labs/jdk-tools`、`labs/sql`、`labs/jakarta-ee11`は未接続である。
3. **部分完了:** 接続済みlabにはstarter、reference solution、固定acceptance script、timeout、clean-upを用意した。今後も同じprotocolを使う。
4. **環境上の注意:** 現在の検証環境ではHTTP/JFRは実行合格。Docker daemonが停止しているためPostgreSQL/containerは環境不足診断まで確認し、実container実行は要確認である。

### Phase 3: 実務capstoneを増やす

1. Spring/Liberty/Quarkusのいずれかで注文APIを完成させるtrack。
2. migration、outbox、retry、logging、healthを1本のprojectへ統合する。
3. failing CI、性能劣化、certificate/API contract更新を含む運用incident演習。
4. 最後にPR、ADR、runbook、incident reportを成果物として残す。

## 10. 最終判断

現状は、Java言語と標準APIを学ぶ教材として非常に強い。さらに、artifact・project・runtime-lab・preflightの導入により、実務教材をsingle-fileへ押し込める技術的制約は解消した。HTTP、JFR、実DB migration、container、既存application改修、logging障害調査、Liberty XML設定と実runtimeでは、説明と評価の整合性が実際に改善している。

一方、必須の非single-file問題は583問中12問であり、Jakarta EE 11、SQL基礎、JDK tool、CI/CD、TLS/OpenAPI/SBOM等は説明の品質に評価がまだ追いついていない。Spring Bootもproject/runtimeの主要経路は実物化したが、自動構成診断、型付き設定、DB/security、version updateまでは到達していない。Open Libertyも通常起動経路は実物化したが、Config上書き、更新運用、InstantOnまでは到達していない。Quarkusも主要経路は実物化したが、Dev Services、build-time設定差、updateまでは到達していない。問題文が専門用語を使っていても、解法が一般的な`switch`、Map、List、整数計算だけなら、その専門技能を測ったことにはならない。

したがって、次の最優先事項は新しいengine開発ではない。既にある4 typeとruntime protocolを使い、
未接続labsを正式課題へ昇格させ、Concept/Coding/Practiceの修了状態を分けることである。現在の丁寧な説明と豊富なJava kataを保ちながら、初心者を「知っている」から「動かせる・壊れたとき直せる」実務レベルへ導く現実的な道筋は、初回レビュー時より明確になった。
