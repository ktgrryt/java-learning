# JUnit・Mavenラボ

本体とは独立した最小プロジェクトです。依存管理、JUnitの書き方、成果物の作られ方を
実際のMavenで確かめます。

## 必要なもの

- JDK 21以降
- Maven 3.9以降
- 初回のみ、依存をダウンロードするためのネットワーク

> JDK 21.0.8 / Maven 3.9.12 / macOS 15 で動作確認

## 手順

```sh
mvn test
mvn package
```

## 成功したらこう出る

```
[INFO] Running cafe.lab.PriceServiceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running cafe.lab.DiscountReportTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`mvn package` まで通すと `target/testing-maven-lab-1.0.0-SNAPSHOT.jar` ができます。

## 確認すること

このラボでは次を本物のJUnitで確かめます。

- `PriceServiceTest`: `@CsvSource` と `@MethodSource` を使ったパラメータ化テスト
- `DiscountReportTest`: `@BeforeEach` による準備と `@TempDir` によるテストデータの隔離・自動削除

全体では9ケース（`PriceServiceTest` が7ケース、`DiscountReportTest` が2ケース）です。
テストクラスの実行順は環境で変わり得るため、どちらが先でも成功するようにしてあります。

1. テストを1つわざと失敗させ、`Failures: 1` と失敗メッセージが
   どのテストのどの行を指すかを読む
2. 読んだら元へ戻し、もう一度 `BUILD SUCCESS` になることを確かめる
3. `target/` の中身を見て、`classes` と `test-classes` が分かれていること、
   JARにテストクラスが入っていないことを確かめる

## Mockitoを依存関係へ入れていない理由

Mockitoは便利ですが、JUnitそのものの必須機能ではありません。このラボではまず、
入力と期待結果の表、テストごとの準備、テスト専用ディレクトリを使った隔離を確認します。
依存先を差し替える演習では、教材第45章の手書きstub・fakeを先に理解してください。

そのうえで、依存インターフェースが多く手書きの準備が読みにくい場合や、通知を1回送るなど
「呼び出し自体」が仕様の場合にMockitoを検討します。実装内部の細かな呼び順まで検証すると、
外から見える結果を変えないリファクタリングでも壊れやすいテストになる点に注意してください。
