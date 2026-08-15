# 実JUnit演習

Java実践・開発基盤編『テストとビルド』のレッスン「実際のJUnitで保守しやすいテストを書く」が採点に使います。

教材画面の`MiniJUnit`では動かせない`@MethodSource`・`@TempDir`・`@BeforeEach`を、実際のJUnitで
動かす演習です。

編集するのは次の2ファイルです。

| ファイル | やること |
|---|---|
| `src/main/java/cafe/lab/DiscountReport.java` | 変更不能な受け入れテストが指摘する2つの不具合を直す |
| `src/test/java/cafe/lab/DiscountReportTest.java` | 一時ディレクトリと表形式の入力で、保守しやすいテストへ書き換える |

`src/test/java/cafe/lab/RequirementsTest.java`は変更できません。前半はDiscountReportの仕様を
実際の入出力で固定し、後半は学習者のテストクラスの形（`@TempDir`のPathフィールド、`@BeforeEach`、
`@ParameterizedTest`＋`@MethodSource`、2件以上を供給するstaticメソッド）を固定します。

## 手で動かす場合

```sh
mvn test
```

直っていないと、こう出ます。

```
[ERROR] Tests run: 5, Failures: 3, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

直すと8件すべてが成功します。`Files.write`は既定で前回の内容を切り詰めます。`APPEND`を足すと
残るので、書き直したはずの報告に古い行が混ざります。

生成物（`target/`）はコミットしません。
