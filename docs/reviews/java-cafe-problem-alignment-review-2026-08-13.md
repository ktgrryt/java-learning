# Java Café 問題・学習目標整合性レビュー

- 初回レビュー日: 2026-08-13
- 現状反映日: 2026-08-14（Phase 1の疑似問題置換とPhase 3の運用統合演習を追加した時点。これ以前はC/D評価解消時点で、JDK標準tool・Maven/JUnit・Jakarta EE 11・CI設定・依存解決・TLS・JPMS・パスワード移行・ヒープ実測・移植性実測・3製品比較まで）
- 対象: 7編・57章・310 lessons・必須611 tasks + 任意発展2 tasks
- 観点: 「問題に正解できること」が、lessonの本題を理解・実践できることを意味するか
- 方針: 現在のJSON、採点条件、問題engine、labs、自動検証結果を読み取りで再確認

## 0. 現在の実装状況

初回レビュー後、問題形式を学習目標へ合わせるための基盤と、代表的なlabsの自動採点接続が実装された。

| type / 単元 | 現在数 | 用途 |
|---|---:|---|
| `single-file` | 579問 | Java文法、標準API、algorithm、純粋domain rule |
| `artifact` | 4問 | Open Libertyの`server.xml`、GitHub ActionsのCI設定、壊さないOpenAPI契約更新、QuarkusのExtension宣言（`pom.xml`）を直接編集・構造検査 |
| `project` | 6問 | Spring Boot/Quarkus API、business app改修、logging障害調査、実JUnitのtest設計、運用の立て直しを複数ファイルで実施 |
| `runtime-lab` | 必須22問 + 任意2問 | Spring Boot、Open Liberty、Quarkus JVM/Native、Jakarta EE 11、HTTP server、TLS、JFR、スレッドダンプとデッドロック、jshellスクリプトとjpackage配布物、Jakarta Dataの宣言、PostgreSQL、container、JDK標準tool、Mavenビルド・依存解決を実際に起動・観測・停止 |
| `preflight` | 9単元 | JDK、Maven、Docker、port等を★対象外で事前確認 |

現在接続されている非single-file問題は次のとおりである。

- `30-2`: 実JVMでGC後の保持量・追い出しの回収・キャッシュの窓・深い入力のスタックを実測
- `50-2`: 同じclass fileをロケール・タイムゾーン・文字集合を変えて実行し、出力が変わらないことを実測
- `37-3`: 実行中JVMからJFRを記録し、`jfr summary`でイベントを確認
- `45-4`: 壊れた`pom.xml`を直し、実`mvn clean package dependency:list`でtest実行・scope解決・JAR内容・版の明示を確認
- `45-4`: `module-info.java`を書き、公開したpackageだけが他moduleから使えることをcompileで確認（JPMS）
- `45-6`: 実JUnitのfailing testから実装を直し、`@TempDir`・`@BeforeEach`・`@MethodSource`で書いたtestを`mvn test`で確認
- `46-5`: PostgreSQL 16でDDL制約、LEFT JOIN・GROUP BY・HAVING、複合index、`EXPLAIN ANALYZE`を確認
- `47-4`: localhostのHTTP serverを起動し、200・404・request timeoutを確認
- `48-4`: 実PBKDF2でパスワード保存を直し、ソルト・方式と設定の版・旧形式の照合・再ハッシュ判定を確認
- `48-5`: Jakarta EE 11対応サーバーへWARを配備し、201・recordのJSON-B変換・InstantのISO-8601・Validation 400・停止を確認
- `51-3`: `javac --release`、`javap -verbose`、`jdeps --print-module-deps`、`java`を実際に動かし、class fileの版・命令・依存module・実行結果を確認
- `51-3`任意発展: `module-info.java`の依存宣言から`jlink`で縮小ランタイムを作り、module一覧と起動を実測（章クリア・★対象外）
- `52-2`: 実依存解決の衝突を直し、実行時エラーの解消と解決後の版のそろい方を`mvn test`・`dependency:list`で確認
- `52-5`: GitHub Actionsのworkflowを直し、JDK固定・test実行・供給網の確認・成果物の保存・再buildしない配備を検査
- `53-6`: Spring Boot・Open Liberty・Quarkusの同一APIを実buildし、実装の出所と配備の形を成果物から読み取る
- `54-2`: PostgreSQL 16 containerへmigrationを適用し、後方互換INSERT・UNIQUE・indexを確認
- `55-5`: Docker imageをbuildし、non-root・read-only・資源制限・readinessを確認
- `56-1`: `keytool`で作った証明書で実TLSサーバーを起動し、SANの一致・ホスト名不一致の拒否・未信頼証明書の拒否・停止を確認
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
process tree停止、構造化check結果を扱う。container imageは自動pullせず、外部環境不足を学習者の
不正解として記録しない。container labは`46-5`・`54-2`・`55-5`のいずれもDocker/Podmanのうち接続可能で必要imageがあるruntimeを選択し、事前確認もどちらか一方で合格する。runtime-labの要件確認は「toolが在ること」ではなく「その配布物で実際にできること」を測る。`jlink`は最小imageを実際に作り、`jfr`は設定つきの短い記録を実際に取って確かめる。どちらも失敗した環境では環境不足として省略し、学習者の不正解にしない。全教材回帰検証はSpring Boot、Open Liberty、Quarkus、Jakarta EE 11のproject/runtimeを含む305 lessons・必須596 tasks＋任意発展2 tasks・2931 cases・373 quizzesで合格した（JDK 25.0.3 / IBM Semeru、Maven 3.9.12、Podman 5.5.2）。JDK 21.0.8（IBM Semeru）でも第23章を含む全章が合格する。環境不足として省略されたのは2問だけである。`37-3`のJFRはOpenJ9で記録を作れないためで、JDK 25は`settings=`や`name=`を含む`-XX:StartFlightRecording`を拒否し、JDK 21は受け付けても記録ファイルを作らない。HotSpot（openjdk 17で確認）は同じ指定で記録できるため、このlabはHotSpot系JDKを要する。`51-3`任意の`jlink`はJDK 25では合格し、JDK 21配布物では`java.base`単体すら作れないため省略される。`46-5`・`54-2`・`55-5`はPodman 5.5.2上の実PostgreSQL 16と実containerで合格した（以前はDocker専用または必要image不足で省略されていた）。任意のQuarkus Native ImageだけはDocker専用のため省略される。

ただし、これは初回レビューの問題を全面解消したことを意味しない。必須596問中571問は依然として
`single-file`であり、Jakarta Data、Git diff、securityの
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

初回は章単位でA 32章、B 11章、C 5章、D 8章だった。現在は保守的にA 33章、B 23章、C 0章、D 0章と評価する。**C評価とD評価は残っていない。**test/build章は実Mavenと実JUnitの追加でB→A、Jakarta EE 11章は実サーバー配備の追加でD→B、チーム開発章は実CI設定と実依存解決の追加でD→B、security/API契約章は実TLS labの追加でC→B、JVM memory章はヒープ実測labの追加でC→B、Javaの成り立ち章は移植性の実測labの追加でD→B、framework選定章は3製品の成果物比較labの追加でC→Bへ改善した。性能測定章は実JFR labの追加でD→B、SQL基礎章は実PostgreSQL labの追加でD→B、Spring Boot章はprojectとruntime-lab追加でD→B、Open Liberty章はXML artifactとruntime-lab追加でD→B、Quarkus章はprojectとJVM runtime-lab追加でD→B、deployment章は実container lab追加でC→B、JDK選定・tool章は実JDK tool labの追加でC→Bへ改善した。Dは説明文が悪いという意味ではなく、主に評価方法の問題である。

現在の重要な判断は次の4点である。

1. **4種類の問題基盤は実装済みで、必須教材移行は25問へ進んだ。** engine不足という構造的blockerは解消したため、今後は各章の到達目標に合わせて既存single-file問題を置換・補完する段階である。
2. **Spring Boot、Open Liberty、Quarkusは実製品を自動採点する段階へ進んだ。** QuarkusはExtension、Config、`@QuarkusTest`、JVM package、REST、Validation、Healthまで接続し、Native buildは任意発展へ分離した。Libertyの更新運用やQuarkusのDev Services/update比較等はまだPractice completeに達していない。
3. **性能、HTTP、SQL基礎、実DB、container、JDK標準tool、Maven/JUnit、Jakarta EE 11、TLS、capstone、loggingでは実物課題が星・合格・進捗へ接続された。** 一方、OpenAPI/SBOM、Jakarta Data、Git diff/静的解析等は疑似問題だけでも章を修了できる。
4. **専門概念と無関係なsource checkは削除した。** framework章と選定・運用章から68件を外し、構文の指定はそれ自体がlesson目標である基礎章だけに残した。domain能力の判定は実物課題が受け持つ。

Criticalな技術誤りではない。問題engineの構造問題は大きく改善したが、「初心者が全発展章で実務レベルに達したと判断できる教材」という目標に対しては、未移行章がHighの課題として残る。

## 2. 良好な領域

次の領域は、説明と問題の学習目標がよく一致している。

- Java基礎編: 変数、演算、分岐、loop、配列、method、class、継承、polymorphism、interface、例外、String、collection、lambda、日時、ファイルI/Oを実際に書かせる。
- 標準Java実践: Stream、generics、BigDecimal、regex、sealed class、pattern matching、Clock等を直接使わせる。
- 並行処理: Thread、Atomic型、CountDownLatch、ExecutorService、CompletableFuture、Virtual Threadをコード上で使用する。
- Servlet/CDI/JPA等: Mini APIではあるが、annotation、lifecycle、request/session、DI、entity state等をコードとして体験させ、本物との差も比較的明示している。
- 総合演習: business appとloggingの既存labが`project`問題へ接続され、複数ファイルの変更、回帰test、SQL migration、PR/incident reportまで自動採点する。
- 実環境演習: HTTP、JFR、PostgreSQL、container、JDK標準tool、Mavenビルド、Jakarta EE 11サーバーの各問題は、加工済み入力ではなく実process・network・DB・生成artifactを検証する。

これらは「説明を読んだ」だけでは通らず、対象概念をコードへ反映しなければ合格できない。今後の再設計では、この品質を発展章にも広げるべきである。

## 3. High: 教材全体に共通する構造問題

### H-01 問題engineの制約は解消、教材移行が未完了

- 重要度: High
- 分類: 構成 / 問題の不備 / 学習目標
- 対象: 主に第22章以降のtool、DB、framework、運用、security章
- 状態: **部分解消**。`artifact`、`project`、`runtime-lab`が実装され、server設定、複数file、process、network、DB、JFR、container、JDK標準tool、Mavenビルド、EE 11サーバー配備を直接採点できる。問題はengineではなく、必須596問中571問がまだsingle-fileである点へ移った。
- 代表例:
  - JDK toolのlessonでは目的語をtool名へ変換する問題が残るが、51-3で`javac --release`・`javap`・`jdeps`・`jlink`を実際に動かす経路へ接続された。`jshell`・`jpackage`・`jdeprscan`は未接続である。
  - SQL lessonで、JavaのMapを使ってJOIN相当の集計を行う。
  - Spring Bootのsingle-file問題ではStarter名をMapから引く課題が残るが、60-5/60-6で実projectと実起動へ接続された。
  - Open Libertyの61-1等では製品理解を一般Javaの判定へ置き換えた問題が残るが、61-2/61-6で設定とruntimeの主要経路は実物へ接続された。
  - JFRは37-3に実記録labが追加されたが、同章のthread dump・JMHは加工済み入力のままである。
- 初心者への影響: 用語と正解文字列は覚えても、実ファイルの場所、commandの失敗、設定の相互作用、ログの読み方を経験しない。実務で最初に遭遇する「起動しない」「設定が効かない」「依存を解決できない」へ対処できない。
- 次の修正: `labs/`の移行は完了したので、次は各labの扱う範囲を広げる。CI/configは`artifact`+実tool検証、Jakarta Dataは`runtime-lab`+実DB、Springはauto-configuration診断やversion updateへ実践範囲を広げる。
- 修正理由: 評価環境を学習目標へ合わせる土台はできたため、章ごとのacceptance criteriaを実物へ移すことが次の律速になる。

### H-02 一部labsは修了条件へ接続、章単位のPractice completeは未実装

- 重要度: High
- 分類: 構成 / 学習目標
- 対象: `labs/`全体と進捗管理
- 状態: **部分解消**。diagnostics、HTTP、実DB、container、JDK tools、Maven/JUnit、Jakarta EE 11、TLS、Spring Boot、Open Liberty、Quarkus、business app、loggingのlabsは正式問題となり、成功時に通常の問題と同じく星・進捗へ記録される。未接続のlabは残っていない。
- 未解消例: **未接続のlabは無くなった**（`labs/`の全13 labが正式問題へ接続済み。接続元をcontent側から機械的に照合して確認した）。残るのは各labが扱う範囲の狭さである。`labs/security-platform`はTLS部分を`56-1`へ接続したが、OIDCとOpenAPI/SBOMの章は未接続のままである。加えて、各labが扱う範囲の狭さも残る。`labs/jakarta-ee11`はREST・JSON-B・Validationまでで、Jakarta Dataと実DBは扱わない。`labs/sql`はDDL・JOIN・集約・index・EXPLAINまで、`labs/jdk-tools`は`--release`・class file版・bytecode・module依存・実行まで、`labs/testing-maven`はtestの実行・scope解決・JAR内容・版の明示と実JUnitの`@MethodSource`・`@TempDir`まで必須接続済みである（`jlink`は任意）。Open LibertyとQuarkusは通常起動の主要経路を接続済みだが、Liberty更新運用・InstantOn、Quarkus Dev Services・updateは未接続である。
- 初心者への影響: 学習者は自然に「章をクリアした＝製品を使える」と解釈する。実際にはcommandを一度も実行していない可能性がある。
- 修正案: 3層表示は実装した（章詳細に概念／コード／実践を並べる）。実践課題は`required`なので、製品名を冠する章では章クリアの条件に既に入っている。残るのは、3層それぞれを独立した修了badgeとして見せるかどうかの判断である。
- 修正理由: 簡略問題自体は導入として有用だが、それを最終評価にしないことで価値を保てる。

### H-03 source checkがdomain能力ではなく一般Javaの字面を測る

- 重要度: High
- 分類: 問題の不備
- 状態: **解消**。artifact/project/runtime-labでXML、Spring annotation・Starter・test report、HTTP、JFR、DB、containerのdomain結果を検査できるようにしたうえで、Java構文が主題でない章のsource checksを外した。`ch60`〜`ch62`から52件、`ch50`〜`ch53`から16件、合わせて68件を削除した。
- 削除した内容: `switch`、`default ->`、`LinkedHashSet`、`getOrDefault`、`new TreeMap`、`Set.of`、`List<String> missing`のような変数名、`static String xxx`というメソッド名の指定、`service.message`。いずれもSpring/Liberty/Quarkusや選定・運用の能力ではなく、Javaの書き方の指定だった。
- 削除の判断: 削除した26タスクはいずれも隠しケースが挙動（重複除去の順序、未知入力の既定値、分岐の網羅）を固定しており、構文の指定は挙動の検査に何も足していなかった。これで`if`とMapのどちらで書いても、`stream`で書いても通る。
- 残したもの: `ch01`〜`ch44`と`ch57`のsource checksは残した。`if`・`for`・`sealed`・`@Entity`・`@WebServlet`・`Files.*`・`StandardCharsets`・`ATOMIC_MOVE`のように、その構文やAPI自体がlessonの到達目標である場合にあたる。framework章のdomain能力は`45-4`・`48-4`・`60-5`〜`62-5`等の実物課題が受け持つ。
- **適用の拡大（2026-08-14）:** 初回の一括削除は`ch50`〜`ch53`・`ch60`〜`ch62`だけで、domainが主題の他の章は手つかずだった。同じ2条件を`ch45`・`ch46`・`ch47`・`ch48`・`ch54`・`ch55`・`ch56`・`ch63`へ当て、**49件を追加で外した**。**`ch30`・`ch37`・`ch38`へも適用（2026-08-14）:** この3章には`sourceChecks`が合わせて**23件**あった（当初「14件」と書いたのは削除対象の数で、総数ではなかった）。3条件で判定し、**14件を外して9件を残した**。

| 残した | 理由 |
|---|---|
| `30-1#1` の`getPackageName`・`getSuperclass`・`getSimpleName` | **条件③に該当。**入力3通りに対する固定出力なので、`switch`で直書きすれば通る。実行中にクラスを調べたかを出力では区別できず、この検査が採点の本体になっている |
| `30-3#1` の`new LinkedHashMap`・`, true)`・`removeEldestEntry` | 課題文が「access-orderの`LinkedHashMap`で動かす」と明示している。access-orderの指定はLRUの機構そのもので、自前の構造で実装しても出力は同じになる |
| `30-3#2` の`implements AutoCloseable`・`try (`・`void close(` | try-with-resourcesが到達目標。`finally`で手で閉じても出力は同じなので、構文の使用は出力では区別できない |

外したのは`Arrays.sort`・`Math.ceil`・`Math.min`・`new TreeMap`・`String.join`・`>= threshold`・`static long percentile(`など、**出力が正しさを固定しているもの**と**正しい別解を弾くもの**である。`Math.ceil`は整数演算でも同じ順位を出せるため、要求すると別解を弾いていた。

`ch01`〜`ch44`のうち`ch32`・`ch34`・`ch57`などは、`AtomicLong`・`CountDownLatch`・`synchronized`・`Executors`・`ByteBuffer.flip`・`CompletableFuture`・`Files.*`のように**APIそのものが到達目標**なので対象外で正しい。

| 章 | 外した件数 | 残した件数と理由 |
|---|---:|---|
| `ch45` テストとビルド | 11 | 14件残す。`@Test`・`assertEquals`・`assertThrows`・`@ParameterizedTest`・`@CsvSource`・`@BeforeEach`はJUnitのannotationそのものが到達目標。`Clock.fixed`と`Instant.now`禁止は「時刻を差し替えられるように書く」というテスト設計の到達目標。`class FakeOrderRepository implements OrderRepository`と`Optional.ofNullable`は手書きfakeがinterfaceの契約を満たすことの指定 |
| `ch46` SQL基礎 | 3 | 0件。domainは`46-5#0`のPostgreSQL runtime-labが受け持つ |
| `ch47` JSONとHTTP | 4 | 0件。domainは`47-4`のHTTP runtime-labが受け持つ |
| `ch48` Jakarta EE 11 | 4 | 0件。`record`での模型化は`ch43`、時刻の解析は`ch19`・`ch44`の到達目標であって Jakarta EE 能力ではない |
| `ch54` 実DB・移行・非同期 | 19 | 0件。`maxAttempts`・`checkpoint = to`・`done.add(`のような変数名と代入式の指定が多かった。domainは`54-2`の移行labが受け持つ |
| `ch55` 設定・コンテナ・可観測性 | 2 | 1件残す。`Locale.ROOT`は、既定ロケールによって出力が変わり採点が環境依存になるのを防ぐため（隠しケースでは検出できない） |
| `ch56` 本番セキュリティとAPI契約 | 4 | 0件。domainは`56-1`のTLS labと`56-5#2`相当の契約判定が受け持つ |
| `ch63` 運用統合演習 | 2 | 3件残す。`Duration.between`と`isAfter`は「文字列で比べない」がlessonの主題。`Math.ceil`禁止は「整数のまま切り上げる」という課題文の明示要求。今回追加した自分の章にも同じ規則を当てた |

外した49件のうち19件は、ひな形が最初から満たしている空振りでもあった（→H-06）。

**条件②が成立しない問題が1件あった。** `45-7#0` は `MiniJUnit` でテストを走らせ、出力の
`PASS / Failures: 0` を期待値と比べる形をとる。ひな形は `@Test` メソッドの中身が空で、
**空のテストは何も検証しないまま成功する**ため、期待出力と一致してしまう。つまり隠しケースは
挙動を固定できておらず、この問題の採点は `sourceChecks` に依存していた。H-05で入れた
「ひな形が最初から合格していないか」の検査がこれを捕まえた。

調べると削除前から穴があった。当時の3件は `@BeforeEach`（ひな形が既に満たす）・
`items.clear(`・`items.add(`（ひな形の `main` に `FixtureTest.items.add(...)` があるため
これも満たす）で、**テスト本体が実際に検証しているかを誰も要求していなかった**。
`items.clear(` を戻し、`assertEquals` を2回以上要求する検査を足して塞いだ。
`MiniJUnit` を使う残り2問（`45-2#0`・`45-6#0`）は、もとから assert を要求しており、
`--strict-starters` でひな形が落ちることを確認した。

**この事例からの一般化:** 削除の判断には条件をもう1つ足す必要がある ―
**③その問題の期待出力が、正しい実装と「何もしない実装」を区別できるか**。
テスト自体を書かせる問題（テストが通ることを出力で見る問題）では区別できないので、
`sourceChecks` を外せない。`sourceChecks` を外すときは `--strict-starters` を必ず通す。

`sourceChecks`は709件（初回レビュー時点）→601件になった。
- 修正理由: 採点条件はlessonの到達目標をそのまま表す必要がある。

### H-04 「知識確認」と「実務能力確認」が同じ星で扱われる

- 重要度: High
- 分類: 構成 / 学習目標
- 対象: 発展編全体
- 状態: **解消（2026-08-14）**。章ごとに「概念（quiz）／コード（`single-file`・`artifact`）／実践（`project`・`runtime-lab`）」の到達状況を分けて表示し、**達成状態を保存**するようにした。あわせて§8.4の実務rubricを、実際に解いた問題から算出して表示する。★は依然として問題ごとに1つだが、章単位の到達度は3層とrubricの両方で見える。
- **3層の永続化:** 層の達成は進捗から導けるが、導出だけにすると**章へ問題が増えた瞬間に過去の達成が未達成へ戻る**。それでは「この章の実践までやり切った」という記録にならない。所有アイテムと同じく、一度達成したら消さない形にした（`ProgressStore#layerCompletions`。キーは`章ID#層`、値は初めて達成した日）。画面は「2026-08-14 に達成（追加分が残り）」のように、記録と残件の両方を出す。数え方はブラウザ側にもあったが、`Curriculum#layerProgress`へ寄せて定義を1つにした。対象0件の層は達成にしない（クイズや実践課題を持たない章でバッジだけ点くのを防ぐ）。実際にクイズを1問足して、達成日が残り`complete`だけがfalseへ戻ることを確認した。検査は`tools/check-layer-completion.sh`。
- **§8.4 rubricの実装:** 5軸（説明／実装／診断／test／判断）を各0〜2点で出す。**問題の型からは5軸を区別できない**（`runtime-lab`は実装と診断の両方を兼ねる）ため、`task`へ任意の`rubric`欄を追加し、非single-fileの**34問へ実際に測っているものを注釈**した。既定の導出は控えめにし、`single-file`と`artifact`は「実装」だけを主張する。

| 点 | 条件 |
|---|---|
| 2点 | その軸を測る問題（クイズ）を全部クリアした |
| 1点 | 半分以上クリアした |
| 0点 | それ未満 |
| — | その章に**その軸を測る手段が無い**（0点とは区別する） |

  実務修了の条件は§8.4のとおり「合計8/10以上かつ実装・診断が各1点以上」だが、**測っていない軸は条件へ数えない**。クイズしか無い章に診断を要求しても意味がないので、測っている軸だけを分母にして同じ割合（80%）で判定する。第1章は説明・実装の2軸だけなので満点で4/4、第57章は5軸すべてが対象で10/10になることを確認した。
- **測れていないこと:** 「自分の言葉で説明できる」「選択理由を記録できる」の中身は測っていない。言えるのは「説明を書く課題（クイズ・報告・ADR）を通した」までである。rubricは*何を通したか*の要約であり、文章や判断の質の評価ではない。
- 初心者への影響: 何を説明でき、何を実行でき、何を診断できるのかが本人にも採用側にも分からない。
- **lesson単位のrubric（2026-08-14）:** レッスンごとにも軸を出すようにした。レッスン一覧の各行へ、そのレッスンが測る軸をタグで添える（「実装」は既定なので出さない ― 全部に付いて情報にならないため）。「この章のどこで診断を学ぶのか」が並びで読める。
  `single-file`問題へも軸を書き下した。**キーワードで機械的に決めない**方針をとった。「判定してください」で拾うと`4-3`（映画館の料金をif/elseで分ける）まで「判断」になり、rubricが嘘をつく。候補を2通り（task文・期待出力の言い回し／レッスン名）で集めてから1問ずつ読み、§8.4の定義へ厳しく当てた。

| 軸 | 当てる条件 | 除外した例 |
|---|---|---|
| `diagnose` | 証拠（ログ・ダンプ・計測結果）から原因を突き止める | `37-5`（ダンプ形式の入力を集計するだけ）、`50-1`・`51-4`（規則の当てはめ） |
| `decide` | 制約のもとで候補から選び、理由を示す | `4-3`（if/elseの練習）、`54-1`（掛け算と比較）、`56-5`（規則の当てはめ） |
| `test` | テストそのものを書く／テストの範囲を決める | ― |

  **全611問を見終えた。** 痕跡（task文の言い回し・レッスン名）で読む対象を117問へ絞り、1問ずつ§8.4の定義へ当てた。大半は誤検出だった ― 「解説には…」で始まる導入文が痕跡語を含む、「テストの点数」の「テスト」（`4-2`）、例外APIの練習で「例外」「失敗」が出る（第13章）、「判定してください」が単なる分岐の指示（`4-1`・`59-1`）など。

| 結果 | 数 |
|---|---:|
| `rubric`欄を書いた | **92問**（`single-file` 60・`runtime-lab` 22・`project` 6・`artifact` 4） |
| 既定のまま（実装のみと判断） | 519問 |
| 軸を測る問題の数 | 実装 556・診断 36・判断 36・test 20・説明 2 |

  **既定の意味が変わった。** これまで`rubric`欄が無いことは「まだ見ていない」を意味したが、いまは
  「見たうえで実装のみと判断した」を意味する。次に問題を足す人は、実装以外の軸を測るなら`rubric`を
  書く必要がある（書き忘れると、その能力が集計から抜ける）。
- 修正理由: 実務レベルはAPI暗記ではなく、実装・診断・判断を組み合わせた能力である。

### H-05 模範解答検証だけでは「絶対に落ちない検査」を捕まえられない

- 重要度: High
- 分類: 検証の不備
- 対象: `tools/verify_solutions.py` と、構成検査つきの問題すべて
- 経緯: 初回レビューには無い項目である。実物課題を12問追加する作業中に、同じ種類の不具合を4回踏んだことで判明した。
- 状態: **解消**。回帰が`artifact`・`project`・`runtime-lab`のひな形を提出し、**合格しないこと**を確かめるようにした。もともとひな形は提出しているので、追加の実行費用はない。`single-file`は既定ではコンパイル確認のままで、`--strict-starters`を付けると全ケースで採点する（所要はおよそ2倍）。事前確認レッスンは、必須項目の不合格を「注意」として出すようにした（合否にはしない。端末ごとに違うため）。
- 見逃されていた実例:
  - `54-2`の`db-outbox-index`は、V3 migrationが作る`ix_outbox_unpublished`を`idx_`で探していた。**誰も合格できない検査**だったが、Docker専用で常に省略されていたため露見しなかった。
  - `48-5`は応答JSONの空白を一律に潰しており、要件に書いた「境界で値を整える」を実際には測っていなかった。
  - `30-2`の実測はキャッシュをローカル変数だけで持っていたため、測る前にGCされ、リークしている実装でも通っていた。
  - `53-6`は失敗メッセージへ実測値をそのまま出しており、1回提出すれば答えを写せた。
  - `28-1`は、この検査を入れた直後に**新たに検出された**。ひな形の`find`と`main`は完成済みで、課題文が求めているのは`@Path`・`@GET`・`@PathParam`・`@Produces`を付けることだけだった。注釈は標準出力に何も影響しないため、3ケースすべてがひな形のまま通っていた。**課題文が要求していることを、採点が一切測っていなかった**。`sourceChecks`（`@Path`2個・`@Produces`・`@GET`・`@PathParam`）を主問題へ追加して解消した。
- なぜ模範解答検証では見つからないか: 4件はいずれも「模範解答は通る」ので、回帰は緑になる。落ちるべきものが落ちるかは、ひな形を提出して初めて分かる。
- 検出力の実測: `--strict-starters`を第1〜5章の前方一致（第1・10〜19・2・20〜29・3・30〜39・4・40〜49・5・50〜56章にあたる）で走らせ、「最初から合格するひな形」は`28-1`の1件だけだった。既定パス（構成検査つき18章）では誤検知なし。
- 残っている穴: ひな形が「偶然通る」`single-file`問題は、既定では検査されない（`--strict-starters`が必要）。事前確認の`pass`は注意止まりで、合否にはしていない。**検査の粒度**も足りない。回帰が見るのは「ひな形が全体として合格するか」なので、`sourceChecks`が複数ある問題では、そのうち1件が空振りしていても他の検査が落ちて隠れる（→H-06）。
- 作業上の注意: `sourceChecks`は`SourceChecker.codeOnly`がコメントと文字列リテラルを空白へ潰した後のソースへ当たるため、`@Path("/products")`のような**リテラルの中身は検査できない**。注釈名と個数で測る。`tools/CheckCount.java`を使えば、回帰を待たずに当たり外れが分かる。

### H-06 ひな形が満たす`sourceChecks`が88件あり、消失を検知する仕組みが無かった

- 重要度: Middle
- 分類: 検証の不備
- 対象: `sourceChecks`を持つ269問と、`content/*.json`を書き換えるすべての作業
- 経緯: 初回レビューには無い項目である。H-05の検査を入れた確認作業中に、作業ツリーとHEADを機械比較したところ、`ch50`〜`ch53`・`ch60`〜`ch62`で68件の`sourceChecks`が消えていた。
- **訂正:** この68件は事故ではなく、**H-03として意図的に削除されたもの**だった（H-03の記述と章ごとの件数が完全に一致する ― `ch60`〜`ch62`が52件、`ch50`〜`ch53`が16件）。当初この節は「説明のつかない消失」と判定し、うち39件を「復元」した。同じ文書のH-03に理由が書かれていたのに読まずに動いたためで、**設計判断を巻き戻す誤りだった**。39件は再削除し、H-03が意図した状態へ戻した。
- 残った本題: 消失そのものは無かったが、**消失を検知する仕組みが無い**ことは事実である。`ContentLoader`は必須キーが欠けると例外で止まるが、`sourceChecks`のような省略可能なキーは消えても何も起きない。模範解答検証もひな形検証も通る（検査が減っただけで、残った検査は正しく動く）。実際に第57章を新規生成した際、キー順リストに`type`と`project`を入れ忘れて`project`問題が`single-file`として読まれた ― このときは`ContentLoader`が止めたが、`sourceChecks`なら黙って消えていた。
- 仕組みでの防止（消失）: `tools/check-content-inventory.sh`と`tools/content-inventory.json`を追加した。レッスンと問題ごとに**個数だけ**（`type`、`sourceChecks`・ヒント・表示/隠しケースの数、artifact/runtime-lab/projectの検査数、`solution`の有無、quiz数、preflightの検査数）を記録し、**減っていたら失敗**させる。増えるのは通常の加筆なので何も言わず、中身は見ないので書き換えは自由。スナップショットはレッスン1件1行（312行・36KB）なので、差分に「どのレッスンの何が減ったか」がそのまま出る。意図した削除は`--update`で記録し、レビューで確認する。サーバーもJDKも使わないので1秒で終わる。`ch60`の`sourceChecks`を全消しする事故を再現して9件すべてを検知すること、問題数の減少・`project`の編集対象ファイルの減少・型の変化も検知することを確認した。
- 空振りの実測: 採点と同じ`SourceChecker.codeOnly`で全587件をひな形に対して数えたところ、**要求（`minimum>=1`）でひな形が満たしているものが84件・56問**あった（H-03の適用拡大前は107件・72問）。禁止（`minimum=0`。「`450`と直接書かない」など）は25件で、こちらはひな形が満たすのが正常である。
- 84件の性質: 多くは`\bclass\s+Rectangle\b`・`\bint\s+area\s*\(\s*\)`・`\bprivate\s+int\s+value\b`のように、**ひな形が与えている宣言**を見張るものである。「学習対象を書いたか」ではなく「与えた足場を壊していないか」を見ており、ガイドの規則（ひな形のままでは通らないこと）は前者だけを想定した書き方になっていた。
- 方針の決定: **規則を精密化して84件は据え置く**こととした。ガイドの箇条を「模範解答は必ず通る」「学習対象を測る検査はひな形のままでは通らない」「足場の見張りはひな形が満たしていてよい」の3つに分け、足すときにどちらの目的なのかを先に決める形にした。
- 仕組みでの防止（空振り）: `tools/check-source-checks.sh`を追加した。サーバーを立てず数秒で全件を1件ずつ数える。既定で現在の84件を基準にし、**超えたときだけ失敗**する（`--list`で全件表示、`--baseline N`で基準変更、`--strict`で1件も許さない）。基準値は`tools/check_source_checks.py`の`BASELINE`にあり、減らしたら下げる。一時的に検査を1件足して基準超えになることを確認済みである。


## 4. 改善した8章（D評価は残っていない）

初回D評価8章のうち、性能測定は実JFR runtime-labによりB、SQL基礎はPostgreSQL runtime-labによりB、Spring Bootはproject＋runtime-labによりB、Open Libertyは`server.xml` artifact＋runtime-labによりB、Quarkusはproject＋JVM runtime-labによりB、Jakarta EE 11は実サーバー配備のruntime-labによりBへ改善した。D評価はすべて解消した。残るC評価1章（framework選定）は、測定値を採点条件にできないという別の問題を抱える。

### 改善-11 `ch50-java-history-platform.json` Javaの成り立ちとプラットフォーム

- 状態: **D→Bへ改善**。`50-2`（Javaの設計目標）へ、同じclass fileを異なるruntime条件で動かすruntime-labを接続した。レビューが挙げた「同じclass fileを異なるruntime条件で動かす最小lab」にあたる。
- 実装済み:
  - 採点は**1回だけコンパイル**し、出来た同じclass fileを既定の環境・`-Duser.language=tr`・`-Duser.timezone=UTC`・`-Dfile.encoding=ISO-8859-1`の4通りで実行して、出力が変わらないことを求める。
  - 出発点は既定値に任せた実装で、トルコ語ロケールでは`"id".toUpperCase()`が`İD`、桁区切りが`1.234,50`になり、ISO-8859-1ではUTF-8のファイルが`cafÃ©`になる。学習者は`Locale.ROOT`・`StandardCharsets.UTF_8`・`ZoneOffset.UTC`の明示へ直す。
  - 別のデータでも実行するので、定数を出すだけでは通らない。
  - 「bytecodeはどのJVMでも読めるが、既定値の違いまでは面倒を見ない」という、この章の主題を実測で示す形にした。
- 検証結果: starterは5 checksのうち4件が不合格、参照解は5 checks合格。いずれも2秒以内。第22章限定回帰は5 lessons・8問・40 casesで合格した。
- 気づき: starterは`-Duser.timezone=UTC`のときだけ偶然通る。「動く環境では気づけない」ことがそのまま出るので、失敗の並びも教材として意味を持つ。
- 残る問題: 50-1の年表sorting、50-4のprefix分類はそのままである。JEP/JSR/OpenJDK/TCKを「記事の主張からどの証拠か判断させる」形へ変える案と、年表をquizへ下げる案は未着手である。

### 改善-05 `ch46-sql-database.json` SQLとリレーショナルデータベース

- 状態: **D→Bへ改善**。`46-5`へ必須のPostgreSQL runtime-labを接続し、SQL基礎章は7 single-file tasks＋1 runtime-labとなった。

- 実装済み:
  - `schema.sql`へPRIMARY KEY、FOREIGN KEY、NOT NULL、UNIQUE、金額・statusのCHECKを直接記述し、不正INSERTが実DBで拒否されることを確認する。
  - `paid_totals.sql`でLEFT JOINのON句へPAID条件を置き、注文のないSoraも0で残す。`high_value_customers.sql`ではGROUP BY後の合計をHAVINGで絞る。
  - 2万件の固定計測データへ`(status, created_at)`複合indexを作り、`EXPLAIN (ANALYZE, FORMAT JSON)`にindex名と実測行数が出ることを確認する。
  - 固定seed、参照解、7つの構造化check、60秒timeout、一意なcontainer名・動的port・trap cleanupを既存runtime protocolへ接続した。Docker/Podmanの両方を利用でき、必要imageがある方を選択する。
- 検証結果: Podman 6.1上の実PostgreSQL 16で第46章限定回帰を実行し、8 tasks・46 cases、SQL runtime単体では7 checksを含む24/24 casesに合格した。starterの意図した失敗、参照解、実index plan、container cleanupまで確認した。全教材回帰も584 tasks・2875 casesで合格した。
- 残る問題: 46-1〜46-4の主問題はJava模型のままであり、2接続を使う分離レベル・deadlock再現やmigration履歴はSQL基礎章には未接続である。migrationの実DB検証は`54-2`で扱う。

### 改善-08 `ch52-team-delivery.json` チーム開発・ビルド・品質管理

- 状態: **D→Bへ改善**。`52-5`へ`.github/workflows/ci.yml`を直す`artifact`問題、`52-2`へ実依存解決の`runtime-lab`を接続した。ビルドツールとCI/CDという章の中心2つが実物になった。
- 実装済み:
  - starterは緑になるworkflowだが、緑であることが何も保証していない。学習者は5点を直す。JDKの版と配布元の固定、`-DskipTests`をやめて`verify`まで進めること、供給網の確認をstepとして入れること、成果物を保存すること、`deploy`が保存した成果物を受け取り作り直さないこと。
  - 最後の条件は章の核心（「本番用にもう一度ビルドすると、検証したものと本番で動くものが別物になる」）をそのまま検査する。buildを2回実行していると不合格になる。
  - 供給網の検査はtoolを限定せず、SBOM・dependency scan系の語をstepとして書けば通る。正しい別実装を不正解にしないためである。
  - YAMLは同梱parserが無いため、`ArtifactValidator`の設計どおりregexで教材固有の検査を行う。UIも「YAMLとして読めた」とは表示しない。構文の妥当性そのものは検査していない。
  - `52-2`では実際の依存衝突を起こす。`jackson-databind 2.18.9`が前提とする`jackson-core`に対し、古い`2.11.0`が直接宣言されているため、Mavenの近い宣言が勝って実行時に`NoClassDefFoundError`になる。解説が書いている「選ばれた版に必要なメソッドがなければ実行時に落ちる」をそのまま起こしている。
  - 検査は3つで、`mvn test`の実行時エラーが消えること、`dependency:list`のjackson系3つが同一版へ解決されること、既知の脆弱性を修正した版へそろえていること（2.18.9以降）である。BOMのimportでもpropertyの共有でも通る。
- 検証結果: `52-5`はstarterが5 checks全て不合格・参照解が5 checks合格。`52-2`はstarterが3 checks不合格（`core=2.11.0`という実測値つき）・参照解が3 checks合格で、いずれも約3秒。第32章限定回帰は6 lessons・10問・41 casesで合格した。カフェ経済も`simulate-cafe.sh`でplain 38.45% / reviewer 18.20%と目標帯に収まることを確認した。
- 残る問題:
  - 52-1・52-3・52-4はPRのfile/line閾値、test時間の足し算、`n*n`のままで、Git diff、merge conflict、test reportを扱わない。
  - 実際のdiffから目的外の変更やsecretを見つけるreview課題、failing CI logの根本原因特定、feature flagで小さくmergeするproject演習は未接続である。
  - `pom.xml`をartifactにする場合、既定namespace付きXMLはXPathで`local-name()`を使う必要がある。`52-2`は静的検査ではなく実行して確かめる方式にしたため、この制約を避けている。

### 改善-01 `ch37-performance-lab.json` 性能測定とJVM計測ラボ

- 状態: **D→Bへ改善**
- 実装済み: `37-3`にruntime-labを追加し、`AllocationDemo`を実JVMで起動、JFR fileを生成し、`jfr summary`でイベントを読み取る。模範解答は`settings=profile`と短い記録時間を選ばなければ合格しない。
- 追加実装（2026-08-14）: 上に挙げていた「意図的にdeadlockするprocessを起動し`jcmd Thread.print`を取得する」を`37-2`へ実装した。`labs/concurrency/deadlock-exercise`を新設し、runtime-lab（6検査）として接続した。学習者は実物のダンプから循環待ちを読み取って`diagnosis.properties`へ記録し、`InventoryService`のロック取得順をそろえて解消する。lock ownerの特定は`ThreadMXBean.findDeadlockedThreads()`の実測値と突き合わせる。
- 残る問題: 37-1、37-4、章末の多くは加工済みデータの集計である。GC log、JMH projectはまだ直接扱わない。
- 改善:
  - JFR file生成とsummaryは完了。次は`jcmd JFR.start`や`jfr print`からallocation/lock/GC eventを抽出する。
  - 小さなJMH benchmarkの誤り（dead code elimination、warmup不足）を直す。
  - GC logを実際に出力させ、そこから停止時間の分布を読ませる。
- 既存資産: `labs/diagnostics`（JFR）と`labs/concurrency/deadlock-exercise`（スレッドダンプ）は正式問題へ接続済み。GC logを追加のruntime checkへ接続する。
- 環境条件: このlabはHotSpot系JDKを要する。OpenJ9系の配布物ではJFRの記録を作れないため、要件確認で短い記録を実測し、作れない環境では環境不足として省略する（★・章クリアの判定へは影響しない）。

### 改善-07 `ch48-jakarta-ee11.json` Jakarta EE 11アップデート

- 状態: **D→Bへ改善**。`48-0`の事前確認に加え、`48-5`へ必須のruntime-labを接続し、`labs/jakarta-ee11`の配備・HTTP結果を必須化した。
- 実装済み:
  - `runtime-exercise`の`UserResource`だけを編集させ、`POST /api/users`の応答をJakarta EE 11対応サーバー上で成り立たせる。`pom.xml`と`server.xml`は変更させない。Feature Managerの扱いは第49章と役割を分けた。
  - Open Liberty 26.0.0.8を一時領域へ準備し、`restfulWS-4.0`・`cdi-4.1`・`jsonb-3.0`・`validation-3.1`の4 Featureで、採点側が確保した動的portへWARを配備する。
  - 実HTTPで、Jakarta REST 4.0の201、JSON-B 3.0がrecordをgetterなしで読み書きすること、`Instant`が既定でISO-8601になること、Bean Validation 3.1がrecordの構成要素の制約でHTTP 400にすることを検査する。終了時は明示停止とtrapを使い、listenerが応答しないことも採点する。
  - 応答JSONの比較は引用符の外の空白だけを詰める。一律に空白を消すと`" Aki "`が`"Aki"`になり、境界で値を整えたかどうかを検査できなくなる。
- 検証結果: 参照解の直接実行で`ee11-war`・`ee11-created`・`ee11-instant`・`ee11-validation`・`ee11-stop`の5 checksが約19秒で成功した。starterは3 checksが個別の理由（200のまま・登録時刻なし・検証なし）で不合格になる。Java Café経由の第46章限定回帰も8問・37 casesで合格した。
- 分かったこと: `labs/jakarta-ee11`のREADMEは「この環境にはアプリサーバーが無いためデプロイ後の応答は未確認」としていた。実配備により、recordのJSON-B変換・`Instant`のISO-8601・recordの構成要素への制約が実際に効くことを確認できた。
- 残る問題:
  - 48-1はPlatform/Web/Core Profileの中身を問わず、Java/Jakartaの整数だけを見る。用途別にProfileを選び、必要APIとの差分と配備先対応を検証する課題は未接続である。
  - 48-2はJakarta Dataを題名にするが、repository interface越しのList検索のままで、Jakarta Data annotation・query derivation・runtime implementationを使わない。実DBを伴うCRUD/page/sortは未接続である。
  - EE 10→11のbuild/server migrationで失敗するprojectを修正する課題も未接続である。

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

## 5. C評価だった章（すべてBへ改善済み）

### 改善-06 `ch51-jdk-version-tooling.json` JDKの選定・互換性・標準ツール

- 状態: **C→Bへ改善**。`51-0`のJDK事前確認に加え、`51-3`へ必須のruntime-labと任意の`jlink` runtime-labを接続した。外部runtimeを使わないため、Docker・network・container imageを一切必要としない。
- 実装済み:
  - `exercise/tools.options`へ、①本番JVMへ向ける`--release`の版、②class fileの版と命令を読む道具・指定、③依存moduleを一覧する道具・指定を書かせる。道具名はallow-listで語彙だけを制限し、目的との対応は検査結果で判定する。取り違えると出力に答えが出ないため不合格になる。
  - `exercise/Menu.java`はJava 21で追加されたAPIと`java.net.http`を使わせる。同じsourceが`--release 17`では通らないことを、class fileが生成されないことで確認する。
  - `javap -verbose`の`major version: 65`で本番と同じ版に落ちていること、`makeConcatWithConstants`で連結がinvokedynamicへ変換されたこと、`jdeps --print-module-deps`が`java.base,java.net.http`を出すこと、`java -cp`で実際に動くことを6つの構造化checkで採点する。
  - 任意発展では`module-info.java`の依存宣言から`jlink`で縮小ランタイムを作り、imageのmoduleが宣言した範囲へ収まることと、そのimageの`bin/java`だけで起動することを4 checksで確認する。`jmods`を同梱しないJDKでは作れないため`required: false`とし、章クリア・★・カフェ報酬・復習の分母に含めない。
  - 学習者のファイル内容が検査結果として読まれないよう、ツール出力は行頭の`JQ_CHECK`だけ無効化して表示する。生成物は`out/`へ隔離する。
- 検証結果: JDK 25.0.3 (IBM Semeru) / macOS 15で、starterは6項目中5項目が意図どおり不合格、参照解は6 checks合格。任意`jlink`もstarterはmodule依存の宣言漏れで不合格、参照解は4 checks合格。Java Café経由の第51章限定回帰は6 lessons・8問＋任意1問・50 casesに合格した。教材の対象版であるJDK 21.0.8 (IBM Semeru)でも必須labは6 checks合格し、任意`jlink`はこの配布物では作れないため環境不足として省略された（失敗ではなく注意として記録される）。
- 残る問題: 51-1・51-2・51-4・章末の主問題は数値比較と分類のままである。`javap`と`jdeps`は必須へ接続したが、`jshell`・`jpackage`・`jdeprscan`はまだ説明とquizだけである。`--enable-preview`の実挙動と、削除済みinternal APIの検出（`jdeps --jdk-internals`）も未接続である。

### 改善-10 `ch30-jvm-memory.json` JVMの実行とメモリ管理

- 状態: **C→Bへ改善**。`30-2`へヒープとスタックを実測するruntime-labを接続した。JDKだけで動き、container・network・外部toolを必要としない。
- 実装済み:
  - 学習者は`RecentOrders`（putとgetは正しいが古い参照が残る）と`OrderTotals`（再帰で合計する）の2ファイルを直す。解説が言う「解放忘れではなく不要な参照の保持」をそのまま起こしている状態から始める。
  - 採点は`java -Xmx128m`で実行し、2万件（1件2KB）投入後のGC後使用ヒープ、追い出したエントリの`WeakReference`回収、直近1000件の窓、20万件でのスタックを測る。
  - GCログは使わない。形式がJVM実装で違うためで、代わりに標準のJMX（`MemoryMXBean`・`GarbageCollectorMXBean`）と標準の`StackOverflowError`だけを見る。OpenJ9でもHotSpotでも同じ意味になる。
  - ヒープを使い切らせない設計にした。OOMで判定するとOpenJ9では19秒かかりJVMダンプまで出るため、保持量の測定に変えて2秒で終わるようにした。
- 検証結果: starterは4 checks全て不合格（保持58MB、追い出しが回収されない、窓が3000件、StackOverflowError）、参照解は4 checks合格。いずれも約2秒。第33章限定回帰は3 lessons・8問・37 casesで合格した。
- 途中で見つけた不具合: 実測コードでキャッシュをローカル変数だけで持っていたため、**測る前にキャッシュごとGCされ**、リークしている実装でも「保持していない」と見えていた。ローカル変数はスコープの終わりではなく最後に使った時点で到達不能になりうる。測り終わるまで参照を置き場へ入れて解決した。
- 残る問題: thread dump、lock owner、class histogram、GC logの読解は未接続である。`jcmd`のサブコマンドはJVM実装によって異なるため、接続するなら実装差の扱いを先に決める必要がある。

### 改善-12 `ch53-framework-options.json` 3製品の設計思想と選定

- 状態: **C→Bへ改善**。`53-6`へ、同じ注文APIをSpring Boot・Open Liberty・Quarkusで実buildし、成果物を見比べるruntime-labを接続した。
- 測定対象から時間とメモリを外した判断: build時間・起動時間・RSSは機械の性能と状態で大きく変わる。合否条件にすると「速いマシンなら合格」になるため、**成果物の構造だけ**を採点対象にした。時間の比較は各自の環境で傾向として掴む扱いにし、その理由を教材へ明記した。
- 実装済み:
  - 3製品の最小構成（同じ`/api/orders`）を1つのworkspaceへ置き、`mvn package`を3回実行する。オフラインの温まったローカルリポジトリなら合計10秒で終わる。
  - 学習者は`comparison.properties`へ、実装の出所（`bundled`／`server`）、配備の形（`single-jar`／`war`／`directory`）、必須条件を先に適用した除外候補と根拠を書く。
  - 採点は成果物から事実を測る。Springのfat JARの`BOOT-INF/lib`と起動用loader、LibertyのWARの`WEB-INF/lib`が空であることと`server.xml`のFeature宣言、Quarkusの`quarkus-app/`の`quarkus-run.jar`と`lib/`分割を読み、回答と突き合わせる。
  - **失敗しても答えは表示しない。** 見るところ（`jar tf`・`cat server.xml`・`ls quarkus-app`）だけを出す。最初の実装では実測値をそのまま失敗メッセージへ出しており、写せば通る状態だったので直した。
- 検証結果: starterは5 checksのうち4件が不合格、参照解は5 checks合格。いずれも約10秒。第47章限定回帰は7 lessons・9問・39 casesで合格した。
- 残る問題: 53-1〜53-5の主問題は点数計算とboolean分類のままである。時間・メモリの実測は、環境差を扱う設計（同一マシン内での相対比較、外れ値の除外など）を決めない限り採点には使えない。

### C-04 `ch61-open-liberty.json`

- 良い点: アプリケーションサーバーの責務、Jakarta EE/MicroProfile、Feature Manager、runtime更新と仕様更新の区別が明確になった。`61-2`では`server.xml`を直接編集する。
- 弱い点: Liberty runtimeを起動していないため、feature不足の起動log、WAR配備、Jakarta REST/CDI/Validation、MicroProfile Config/HealthのHTTP動作をまだ証明しない。
- 改善: `labs/frameworks/open-liberty`をruntime-labへ接続し、設定修復→起動→test→HTTP→停止を採点する。

### 改善-09 `ch56-security-api.json` 本番セキュリティとAPI契約

- 状態: **C→Bへ改善**。`56-1`へ実TLSのruntime-labを接続した。JDKだけで動き、container・network・外部CAを必要としない。
- 実装済み:
  - 学習者は`certificate.options`（dname・SAN・有効期間）と`TrustConfig.java`（クライアントが相手を信じる条件）の2ファイルを直す。starterの`TrustConfig`は「すべての証明書を信頼する」実装で、解説が本番では使えないと書いている状態そのままである。
  - 採点時に`keytool`で3つの証明書と1つのtruststoreを作り、JDKのHTTPSサーバーを3回起動して実TLSで接続する。①正しい証明書へ200、②別ホスト向けの証明書（**truststoreへ入れてある**）をホスト名不一致で拒否、③SANは合うがtruststoreに無い証明書を拒否、④停止の4点を確かめる。
  - ②と③を分けているのが要点である。②はチェーンではなく`No subject alternative DNS name matching localhost found`で落ち、③は信頼の起点が無くて落ちる。「すべて信頼する」ままだと③に繋がってしまうため不合格になる。
- 検証結果: starterは3 checks不合格（SAN不足・接続不可・未信頼を拒否できない）、参照解は5 checks合格。いずれも約9秒。第54章限定回帰は5 lessons・8問・37 casesで合格し、検証後にTLSサーバーのprocessが残らないことも確認した。
- 途中で見つけた不具合: サーバー起動関数をコマンド置換で呼ぶと、subshellの`$!`が親へ残らずプロセスを停止できない。あわせて、未信頼用の証明書のSANを学習者の値から作っていたため、拒否の理由がホスト名不一致にすり替わり「すべて信頼する」実装を検出できていなかった。どちらも修正済みである。
- 残る問題: OpenAPI/SBOM lessonは変更名の分類のままで、contract diffやSBOM生成・脆弱性scan結果のtriageは未接続である。証明書の期限切れ（expired）とmTLS、`openssl s_client`の出力読解も扱わない。

## 6. B評価の中で個別修正したいlesson

以下は章全体を作り直す必要はないが、特定lessonの主問題を置き換えるとよい。

| 重要度 | lesson | 現在の問題 | 問題点 | 推奨置換 |
|---|---|---|---|---|
| Low | 21-5 Mavenとproject | version文字列比較・競合判定 | 単独lessonではpomを扱わない | **章外で解消済み:** 45-4で壊れた`pom.xml`を直し、`mvn clean package dependency:list`を実行する |
| Low | 45-4 Mavenと成果物 | dependency文字列の重複判定 | 導入問題だけではartifactを確認しない | **解消済み:** 同lessonの2問目で実Mavenのscope解決・JAR内容・版未指定の警告を検査する |
| Low | 45-5 中間演習 | 自作mini test runner | JUnitを学んだ直後にrunner実装へ戻る | **章内で解消済み:** 45-6の3問目で実JUnitのfailing testを読み、実装を直して`@MethodSource`・`@TempDir`を書く |
| Low | 47-2 HttpClient | requestを組み立てるが通信しない | 単独lessonでは通信しない | **章内で部分解消済み:** 47-4 runtime-labでlocal serverへ200・404・timeoutを実通信する |
| Low | 54-2 schema migration | 1問目は`expand`等を固定文字列へ変換 | 導入問題だけでは実migrationを設計しない | **解消済み:** 同lessonの2問目でPostgreSQLへV1〜V3を適用し、旧INSERT・UNIQUE・indexを確認する |
| Medium | 54-1 実DB検証 | connection budgetの割り算 | lessonのDB製品差、migration、commit behaviorを測らない | **解消済み（2026-08-14）:** 応用問題として実PostgreSQLのruntime-labを接続した。ロールバック方式とtruncate方式を検証内容へ割り当てさせ、**間違えると実DBがそのテストを落とす**（正解表を持たない）。主問題も接続予算の破綻判定へ差し替えた |
| Medium | 38-2 可観測性 | status/latency集計 | trace/log correlationが題名に対して弱い | **解消済み（2026-08-14）:** 応用問題（主問題と重複するendpoint別集計）を、metricのSLO違反→該当traceの特定→spanツリーの最深errorまで辿る→そのspanのログ、という相関演習へ置換した |
| Low | 58-5 PRで引き継ぐ | 5個のboolean checklist | 単独問題では実PR evidenceを作らない | **章内で解消済み:** 58-6 projectで`PR.md`を編集し、実装・migrationと同じacceptance testsで検証する |

### §6の残り2件の対応（2026-08-14）

`54-1`と`38-2`を処理し、§6の表で「推奨置換」のまま残っていた項目は無くなった。

**`54-1`（実DB検証）** ― 新lab `labs/integration-data/verification-exercise` を作り、応用問題として接続した。
学習者は`strategy.properties`で、2つのテストへ`rollback`と`truncate`を割り当て、1つの観察結果を記録する。

| 検査 | 実DBが強制すること |
|---|---|
| `strategy-outbox` | 別の接続から見えるかを確かめるテストを`rollback`にすると、外からは0件しか見えず落ちる。`psql`の呼び出し1回が接続1つなので、本物の別接続である |
| `strategy-deferred` | `DEFERRABLE INITIALLY DEFERRED`の一意制約のもとで「途中の重複が許される」ことを確かめるテストを`truncate`にすると、コミットの瞬間に制約が検査されて違反で落ちる |
| `observed-sequence` | `BIGSERIAL`の採番はトランザクションの外側で進む。ロールバックしても戻らないので次のIDは2になる。記録した値を実測と突き合わせる |

**正解表を持たない**のが要点である。方式を入れ替える／未記入／観察を誤る、の3通りを実際に提出して、
それぞれが落ちることを確認した。「どちらか一方に統一しようとすると必ずどちらかが検証できなくなる」という
このlessonの主題が、実DBの挙動として現れる。主問題も`(max-reserved)/n`の割り算から、
**各インスタンスが自分のプールを持つため合計が上限を超えうる**という運用上の規則の判定へ差し替えた。

**`38-2`（可観測性）** ― 応用問題は主問題と同じ集計をendpointごとに繰り返すものだったので、
metric→trace→logの相関演習へ置換した。しきい値を超えた分を見つけ、その分にerrorのspanを持つtraceを選び、
spanの木を辿って**いちばん深いerror**まで降り、そのspanに紐づくログだけを出す。
「根のspanは内側の失敗を被るので、原因は深いところにある」という読み方が解法に必要になる。
解説にもspanの親子関係と絞り込みの順番を追記した。この置換で`new TreeMap`・`computeIfAbsent`という
一般Javaの字面を測る`sourceChecks`も消えた（→H-03）。

**残った発見:** `ch30`（JVM memory）・`ch37`（性能測定）・`ch38`（可観測性）には、H-03の掃除が及んでいない
字面検査が残っている（`Arrays.sort`・`Math.ceil`・`new TreeMap`・`String.join`・`>= threshold`など、
合わせて14件）。H-03の適用範囲は「domainが主題の章」としたが、実際に掃除したのは`ch45`〜`ch48`・
`ch50`〜`ch56`・`ch60`〜`ch63`であり、この3章は対象に含めていなかった。
`ch32`（並行処理）・`ch34`（非同期IO）・`ch57`（ファイルIO）は`AtomicLong`・`CountDownLatch`・
`synchronized`・`Executors`・`ByteBuffer.flip`・`CompletableFuture`・`Files.*`のように
**APIそのものが到達目標**なので対象外で正しい。

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
| 22 Javaの成り立ち | B | 歴史・分類の問題は残るが、同じclass fileをロケール・タイムゾーン・文字集合を変えて動かし、移植性が保証する範囲を実測する |
| 23 JDK選定・tool | B | 名称分類の問題は残るが、`javac --release`・`javap`・`jdeps`・`java`を実機で動かし、class fileの版・命令・依存module・実行を検証。`jlink`は任意発展。`jshell`/`jpackage`は未接続 |
| 24 Stream/Optional | A | Stream pipelineを直接実装 |
| 25 generics/collection設計 | A | wildcard、erasure、queue等を直接実装 |
| 26 数値/text | A | BigDecimal、BigInteger、regex、localeを直接使用 |
| 27 型pattern/metadata | A | sealed、pattern、reflectionを直接使用 |
| 28 実務date/time | A | Instant/Zone/DST/Clockを直接使用 |
| 29 test/build | A | JUnit部分は直接。Maven部分も実`mvn clean package dependency:list`でtest実行・scope・JAR内容・版の明示を検証し、実JUnitで`@MethodSource`・`@TempDir`を書かせる。JPMSはmodule境界がcompileで守られることまで確認 |
| 30 SQL/RDB | B | Java模型に加え、PostgreSQLでDDL制約・LEFT JOIN・HAVING・複合index・EXPLAIN ANALYZEを直接検証 |
| 31 JSON/HTTP | B | HttpClientに加えlocal serverへの実通信・200/404/timeoutをruntime-labで確認。JSON libraryは未導入 |
| 32 team delivery | B | 数値・boolean判定の問題は残るが、CI設定を実物として直し、実依存解決の衝突を`mvn`で解消する。Git diff・test reportは未接続 |

### JVM・並行処理・性能編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 33 JVM memory | B | class loadingは直接。heapはGC後の保持量・追い出しの回収・深い入力のスタックを実JVMで実測。thread dumpとGC logは未接続 |
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
| 46 Jakarta EE 11 | B | 一般Javaへ置換した問題は残るが、実EE 11サーバーへWARを配備し201・record JSON-B・Instant・Validation 400・停止を検証。Jakarta Dataとmigrationは未接続 |

### 業務framework編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 47 framework選定 | B | 架空scoreの問題は残るが、3製品を実buildして実装の出所と配備の形を成果物から読み取り、必須条件を先に適用させる。時間・メモリは採点対象外 |
| 48 Spring Boot | B | Starter・DI・Validation・設定・@WebMvcTestをprojectで編集し、実JARのAPI・400・health・停止をruntime-labで確認。自動構成診断と更新演習は未接続 |
| 49 Open Liberty | B | server.xmlを直接編集し、実WAR・6 Feature起動ログ・REST・Validation 400・MicroProfile Health・停止をruntime-labで確認。更新運用とInstantOnは未接続 |
| 50 Quarkus | B | Extension・Config・@QuarkusTestをprojectで編集し、JVM package・REST・Validation・Health・停止をruntime-labで確認。Native実buildは任意発展。Dev Services/updateは未接続 |

### 本番運用・security編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 51 実DB/messaging/batch | B | PostgreSQL migrationを実containerで検証。message brokerと実batch runtimeは未接続 |
| 52 resilience/observability | B | retry等の核心は実装するが実library・telemetryは不足 |
| 53 deployment/observability | B | 実containerをnon-root・read-only・資源制限で起動しreadiness確認。telemetryは未接続 |
| 54 security/API契約 | B | JWT/authzは良い。TLSは実サーバーでSAN一致・ホスト名不一致の拒否・未信頼の拒否を検証。OpenAPI/SBOMは未接続 |

### 総合演習編

| 章 | 評価 | 所見 |
|---:|:---:|---|
| 55 business app capstone | A | project問題で既存code、SQL migration、PR、11 testsを統合 |
| 56 logging/incident | A | project問題で実log、sanitize、timeline、incident report、11 testsを統合 |
| 57 運用統合演習 | A | project問題で契約違反・N+1・上限付き再試行・冪等キー・readiness・expand移行・RUNBOOK・ADRを13 testsへ統合。artifact問題で壊さないOpenAPI更新。速さは秒でなく問い合わせ回数と待ち時間の記録で測り、文書は節ごとの事実の位置で測る |

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

状態: **実装済み（2026-08-14）**。章詳細でConcept/Coding/Practiceを別々に数えて表示し、実践の残件は
「章クリアに必要」と示す。数え方は問題の種類から導出する（quiz／`single-file`・`artifact`／
`project`・`runtime-lab`）ため、教材側に新しい項目を書く必要はない。数え方の定義は
`Curriculum#layerProgress`の1箇所にある（以前はブラウザ側にも同じ処理があった）。
**達成した層は日付つきで保存**し、章へ問題が増えても記録は消さない（→H-04）。
rubricは章単位・lesson単位の両方で出す（§8.4）。軸の注釈は全611問を見終えており、
92問へ書いてある（残りは実装のみと判断した）。

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

**実装済み（2026-08-14）。** 5軸を各0〜2点で算出し、章詳細へ表示する。判定に3つの決めごとを置いた。

1. **軸は教材側に書く。** 問題の型からは5軸を区別できない（`runtime-lab`は実装と診断を兼ねる）ので、
   `task`へ任意の`rubric`欄（`explain` / `implement` / `diagnose` / `test` / `decide`）を追加し、
   **92問**（`single-file` 60・`runtime-lab` 22・`project` 6・`artifact` 4）へ実際に測っているものを
   注釈した。全611問を1度は見ており、書いていない519問は「見たうえで実装のみ」である。既定の導出は控えめにして、`single-file`と`artifact`は「実装」だけを主張する。
   注釈はキーワードで機械的に決めず、1問ずつ読んで§8.4の定義へ当てた（→H-04）。
   章単位とlesson単位の両方で表示する。
2. **測っていない軸は0点と区別する。** その章に手段が無いこと（`—`）と、測ったが達成していないこと（0点）は別である。
   条件の分母も、測っている軸だけにする。クイズしか無い章に診断を要求しても意味がない。
3. **測れないものは主張しない。** 「自分の言葉で説明できる」「選択理由を記録できる」の中身は測っていない。
   言えるのは「説明を書く課題を通した」までである。rubricは*何を通したか*の要約であって、
   文章や判断の質の評価ではない。

## 9. 改訂優先順位

### Phase 1: 誤った達成感を防ぐ

1. **完了:** preflightを「準備・★対象外」として分離表示し、章ごとに概念／コード／実践の到達状況を分けて表示するようにした。
2. **部分完了:** Spring Boot、Open Liberty、Quarkusはproject/artifact/runtime問題を通常進捗へ接続した。Native Imageは任意発展として通常進捗から分離した。
3. **完了:** 37-3、46-5、47-4、51-3、54-2、55-5、60-5、60-6、61-2、61-6へ実物問題を追加し、2026-08-14に残っていた5件を処理した。

| 問題 | 置換前（疑似問題） | 置換後 |
|---|---|---|
| `37-2` | スレッドダンプの行からBLOCKEDを抽出して並べる | **runtime-lab（6検査）を追加**。必ずデッドロックするコードを動かし、`jcmd <pid> Thread.print`で実物のダンプを取る。読み取った事実を`diagnosis.properties`へ書き、`InventoryService`のロック取得順をそろえて解消する。lab は `labs/concurrency/deadlock-exercise` |
| `62-1` | 要件→Extension名のMap引き | **artifact（8検査）へ置換**。実際の`pom.xml`へExtensionを宣言する。BOMを残すこと、`<version>`を書かないこと、`groupId`が`io.quarkus`であること、要件に無いExtensionを足さないことを`xpath`で検査 |
| `50-1` | 年表を昇順ソート | **class file版と実行可否**へ書き換え。メジャー版 = リリース番号 + 44 から`UnsupportedClassVersionError`と必要リリースを判定する |
| `51-4` | 状態→ラベルのMap引き | **previewの版一致規則**へ書き換え。`TOO_OLD` / `STANDARD` / `PREVIEW_OK` / `VERSION_MISMATCH`を判定する。「プレビューで作ったclass fileは作った版のJVMでしか動かない」を計算で扱う |
| `46-2` | Javaで LEFT JOIN + GROUP BY を再実装 | **置換しない。** `46-5`のlabが同じ章で実SQL（LEFT JOIN・HAVING・複合index・EXPLAIN ANALYZE）をPostgreSQL 16で実行しており、同じ章に2つ目のcontainer依存の必須問題を足しても新しく測れる能力が無い。代わりに課題文へ「作るのはSQLではなくJavaの実装で、ここが通ってもSQLが書ける確認にはならない」と明記した（Phase 1の主題である誤った達成感の防止にあたる） |

**JVM差の扱い:** `jcmd Thread.print`の出力は実装で変わる。検証に使ったOpenJ9では`Found one Java-level deadlock`の節が出ず、書式も`"checkout-worker" ... BLOCKED on DeadlockDemo$StockTableLock@... owned by "restock-worker"`だった。そのため**正解の値は`ThreadMXBean.findDeadlockedThreads()`で測り**（仕様で決まるためJVMによらない）、ダンプ側はどちらにも出るもの（スレッド名・ロックのクラス名・`BLOCKED`）だけを確かめている。

**デッドロックを毎回起こす仕掛け:** 参照用コードは`CountDownLatch`で「両方が1つ目のロックを持った状態」を作ってから2つ目へ進む。`TableLock`はロック取得後に40ミリ秒待つ。取得順が逆なら必ず詰まり、そろっていれば必ず通るので、採点が実行タイミングに左右されない。

**逃げ道の検証:** `37-2`は5通り（順序をそろえる／逆順で統一する別解／在庫ロックを取らない／ロックを一切取らない／ひな形）、`62-1`は6通り（ひな形／模範解答／版を個別に書く／要件外を足す／BOMを壊す／`groupId`を誤る）を実際に提出し、正しい別解が通り、抜け道が落ちることを確認した。
4. **完了:** domainと無関係なsource checkを削除し、domainの結果はartifact/project/runtime-labの受け入れ条件へ移した。初回に`ch50`〜`ch53`・`ch60`〜`ch62`から68件、2026-08-14に`ch45`〜`ch48`・`ch54`〜`ch56`・`ch63`から49件、合わせて117件を外した（→H-03）。`ch01`〜`ch44`と`ch57`は、構文やAPI自体が到達目標なので残している。`ch30`・`ch37`・`ch38`も2026-08-14に掃除した（23件のうち14件を外し、出力では区別できない9件を残した。→H-03）。

### Phase 2: 既存labsを正式な問題へ昇格

1. **完了:** `labs/diagnostics`、`labs/http-client`、`labs/integration-data`、`labs/delivery`、`labs/frameworks/spring-boot`、`labs/business-app-capstone`、`labs/logging-investigation`を自動採点へ接続した。
2. **完了:** Open Liberty、Quarkus、`labs/sql`、`labs/jdk-tools`、`labs/testing-maven`、`labs/jakarta-ee11`、`labs/modules`、`labs/security`、`labs/security-platform`を正式問題へ接続した。`labs/`に未接続のlabは無い（content側の`source`一覧と`labs/`のディレクトリ一覧を照合して確認）。
3. **部分完了:** 接続済みlabにはstarter、reference solution、固定acceptance script、timeout、clean-upを用意した。今後も同じprotocolを使う。
4. **環境上の注意:** container labはDocker/Podmanのどちらでも動く。検証に使うJDK配布物で可否が変わる項目があり、いずれも要件確認で実測して環境不足へ分離した。`37-3`のJFRはHotSpot前提で、IBM Semeru 25 (OpenJ9)は`settings=`を含む`-XX:StartFlightRecording`を認識せず、Semeru 21 (OpenJ9 0.53)は受け付けても記録ファイルを作らない。逆に`51-3`任意の`jlink`はSemeru 25で作成でき、Semeru 21の配布物では`java.base`単体すら`invalid section: __MACOSX`で作れない。`54-2`と`55-5`は`requiredTools`が`docker`のみでscriptも`docker`直書きだったため、Podmanしかない環境では実行できなかった。`46-5`と同じ`docker-or-podman`選択へそろえ、事前確認にも`docker-or-podman`を追加して解消した。Podman 5.5.2上で両labの参照解が合格し、`54-2`の`db-outbox-index`が実際には誰も合格できない検査だったことも判明して直した（V3が作る`ix_outbox_unpublished`を`idx_`で探していた）。任意のQuarkus Native Imageだけは今もDocker専用である。

### Phase 3: 実務capstoneを増やす

新章`ch63-operations-capstone.json`（画面上は第57章「運用統合演習」）と`labs/operations-capstone`を追加して着手した。5 lessons・11問（`project`必須1問、`artifact`必須1問、`single-file` 9問）で、`business-capstone`編の3章目にあたる。

1. **到達済み（既存）:** ch60〜62のproject/runtime-labが、Spring Boot・Open Liberty・QuarkusでのAPI実装と実起動を担う。
2. **部分到達 → 補完済み:** 着手時に実物を確認したところ、`ch58`が既にmigration・outbox・冪等キー・監査ログ・PRを11テストで1本のprojectへ統合していた。欠けていたのは**readiness/health**と**上限付きリトライ（バックオフ）**の2つで、これを新章のprojectへ入れた。ch58の焼き直しを作ることは避けた。
3. **到達済み:** 運用incident演習を新章のprojectとして実装した。**failing CI**（最初に11/12が落ち、その並びが未達の受け入れ条件の一覧になる）、**性能劣化**（配備後に入ったN+1）、**API contract更新**（並び順の契約違反の修復と、壊さないOpenAPI更新をartifactで別途）を含む。証明書の更新は`56-1`のTLS runtime-labが実測で担っているため、この章では重複させていない。
4. **到達済み:** 成果物は3章で揃った。ch58がPR、ch59がincident report、新章が**RUNBOOK**と**ADR**である。ADRは「選ばなかった案とその理由」を書くことを採点条件にした。

**性能を決定的に測る方法。** 秒では測らない（機械や負荷で結果が変わり、同じ実装が通ったり落ちたりする）。問い合わせ回数（`CountingOrderQueryPort`）と、待つつもりだったミリ秒（`RecordingSleeper`）を数える。1件ずつ引く実装とまとめて引く実装の差は回数にそのまま出るので、どの環境でも同じ結果になる。§10で述べた「専門用語を使っていても一般的なswitchやMapで解けるなら測ったことにならない」という指摘に対し、この章は測る対象を実装の振る舞いへ寄せている。

**検証結果:** `run-tests.sh app`で12/13が失敗し、`reference`で`tests=13 passed=13 failed=0`。`--only 63 --strict-starters`で11問すべて模範解答が通りひな形が落ちる（53 cases・うち隠し28・quiz 9問）。`--only 58 59 63`で総合演習編3章（17 lessons・27問・129 cases）が合格。`sourceChecks`の要求系空振りは増えていない（この時点では107件。のちにH-03の適用拡大で88件へ下がった）。カフェ経済はこの時点で`BALANCE OK`・plain 37.98%・コイン未解放40.92%・reviewer 18.53%（`37-2`のlab追加後は37.38% / 40.83% / 18.19%）。

**文書の採点をどこまで測るか。** 当初は「必要な節があり、具体値がどこかに含まれる」までしか見ていなかった。これは**全節を空にして事実を1箇所へ並べ、却下した案を「なし」と書いただけで合格する**状態であり、実際に手抜き文書を作って合格することを確認した。そこで検査を`RUNBOOK`と`ADR`の2件へ分け（12→13 tests）、`## 見出し`で本文を切り分けてから次を測るようにした。

| 測ること | 弾かれる書き方 |
|---|---|
| 事実が正しい節にある（時刻とエラー名は「検知」、`queries=`と配備の版は「確認」、戻す先の版は「切り戻し」） | 事実をまとめて1箇所へ並べる |
| 各節の本文に実質20字以上ある | 見出しだけ残して中身を空にする |
| 節どうしが同一本文でない | 節を埋めるために他の節を写す |
| 切り戻しの目安が数と単位を持つ（`\d+\s*(%|ms|秒|分)`） | 「様子を見て戻す」 |
| 却下した案が行頭`- `の箇条書きで2件以上あり、各項目が実質30字以上 | 「なし」「キャッシュ。却下。」 |

4通りの回避（節の写し、定性的な目安、案の名前だけ、事実の位置違い）を実際に作り、それぞれが個別に落ちることを確認した。

**測れていないこと:** 文章の巧拙、論理の妥当性、書かれた理由が本当に妥当かどうかは測っていない。却下理由の有無は**分量による代理指標**であり、長く書けば通る。理由の中身を測ろうとして「ため・から・ので」のような接続表現を要求する案は採らなかった ― 手元の参照解でも3件の却下案のうち2件がこれらの語を使わずに理由を書けており、良い文章を落とす検査になるためである。ここは自動採点の限界として残す。

## 9.5 labの範囲拡張（2026-08-14）

§10で「説明の品質に評価が追いついていない」として挙げた6項目について、**決定的に採点できるか**を
実測してから着手した。2件を実装し、4件は見送った。見送りは判断の記録として理由を残す。

### 実装した2件

| 項目 | 接続先 | 検査 | 決定性の担保 |
|---|---|---|---|
| **jshell / jpackage** | `51-3`（応用・必須） | 5件 | `jshell -q --execution local`でスクリプトを評価する。値は環境変数で渡し、**違う値で2回動かす**ので定数を直接書くと2回目で外れる。`jpackage --type app-image`で作った配布物を実際に起動し、出力を確かめる |
| **Jakarta Data** | `48-5`（応用・必須） | 5件 | `jakarta.data-api`と`jakarta.persistence-api`だけを依存に持ち、実装は入れない。`javac`がannotationの位置・戻り値の型・型引数を検査し、宣言の形（`@Repository`・`CrudRepository`・`@Find`・`@By`・`@OrderBy`・`@Query`）をコメント除去後のソースで確かめる |

`jpackage`はプラットフォーム側の道具に依存するので、要件確認の段で**最小のapp-imageを実際に作って**
可否を判定する（`canBuildAppImage()`）。作れない環境では環境不足として省略する。`jlink`・JFRと同じ方針である。

Jakarta Dataは**DBへ問い合わせて結果が返るかは確かめていない**。それには実装とDBが必要で、
宣言が正しいことと問い合わせが意図どおり動くことは別である。この線引きは課題文とREADMEへ明記した。

### 見送った4件

| 項目 | 理由 | 再検討の条件 |
|---|---|---|
| **GC log** | **書式がJVM固有で、決定的に採点できない。** 検証環境（IBM Semeru 25 / OpenJ9）で`-Xlog:gc`を渡すと終了コードは0だが、出力はOpenJ9独自のXML（`<?xml version="1.0" ?>`）で、HotSpotの`[gc]`行とは別物だった。停止時間そのものも実行ごとに変わる。以前「GCログ形式・build/起動時間・RSSは採点から外す」と決めた判断を維持する | HotSpot限定の任意課題として、要件確認で書式を実測して分離するなら可能。ただし`30-2`のヒープ実測labと測る対象が重なる |
| **JMH** | **実行時間は決定的に測れない。** dead code eliminationの有無はスコアの差として現れるが、しきい値が機械依存になる。構造（`@Benchmark`・`Blackhole`・`@Warmup`）は測れるが、それはannotationの字面検査であり、benchmarkが正しく測れているかの保証にはならない | 「benchmarkが完走し、結果行を出す」ところまでを採点対象にするなら可能。値の比較はしない前提を明記する必要がある |
| **Liberty InstantOn** | **この環境では実行できない。** InstantOnはLinuxのCRIU（checkpoint/restore）に依存する。検証環境はdarwinで、常に環境不足として省略されるだけの問題になる | Linux上のCIで走らせる前提に変えるなら可能。ただし学習者の手元では大半が省略される |
| **Quarkus Dev Services** | **費用に見合わない。** コンテナ実行環境が必須で、既にある`62-5#3`（Docker専用のNative課題）もこの環境では常に省略されている。Quarkusのbuildは所要も長く、必須問題のtimeout上限60秒に収まらない恐れがある | 任意発展（timeout上限600秒）として、Podmanでも動く形に落とすなら可能 |

**この整理の意味:** 「labが無い」ことと「labを作れない」ことは違う。前者は作業の残りだが、後者は
採点の方針（決定的に測れるものだけ測る）から出る帰結である。GC logとJMHは後者にあたるので、
残件一覧では「未実装」ではなく「決定的に測れないため対象外」として扱う。

## 10. 最終判断

現状は、Java言語と標準APIを学ぶ教材として非常に強い。さらに、artifact・project・runtime-lab・preflightの導入により、実務教材をsingle-fileへ押し込める技術的制約は解消した。HTTP、JFR、実DB migration、container、既存application改修、logging障害調査、Liberty XML設定と実runtimeでは、説明と評価の整合性が実際に改善している。

一方、必須の非single-file問題は611問中32問であり、SBOM、Git diff/test report等は説明の品質に評価がまだ追いついていない（OpenAPIは第57章のartifact問題で、壊さない契約更新までは測るようになった）。SQL基礎は実DBへ接続したが、分離レベルやdeadlockの再現までは到達していない。JDK標準toolは`--release`・`javap`・`jdeps`・`java`・`jlink`・`jshell`・`jpackage`まで実物化した（→§9.5）。previewは版一致規則を計算で扱う形にしたが、`--enable-preview`を付けた実compileまでは到達していない。Spring Bootもproject/runtimeの主要経路は実物化したが、自動構成診断、型付き設定、DB/security、version updateまでは到達していない。Open Libertyも通常起動経路は実物化したが、Config上書き、更新運用、InstantOnまでは到達していない。Quarkusも主要経路は実物化したが、Dev Services、build-time設定差、updateまでは到達していない。問題文が専門用語を使っていても、解法が一般的な`switch`、Map、List、整数計算だけなら、その専門技能を測ったことにはならない。

したがって、次の最優先事項は新しいengine開発ではない。既にある4 typeとruntime protocolを使い、
未接続labsを正式課題へ昇格させ、Concept/Coding/Practiceの修了状態を分けることである。現在の丁寧な説明と豊富なJava kataを保ちながら、初心者を「知っている」から「動かせる・壊れたとき直せる」実務レベルへ導く現実的な道筋は、初回レビュー時より明確になった。
