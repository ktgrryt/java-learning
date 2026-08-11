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
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`mvn package` まで通すと `target/testing-maven-lab-1.0.0-SNAPSHOT.jar` ができます。

## 確認すること

`PriceServiceTest` には正常値、境界値、パラメータ化テスト、例外テストがあります。

1. テストを1つわざと失敗させ、`Tests run: 6, Failures: 1` と失敗メッセージが
   どのテストのどの行を指すかを読む
2. 読んだら元へ戻し、もう一度 `BUILD SUCCESS` になることを確かめる
3. `target/` の中身を見て、`classes` と `test-classes` が分かれていること、
   JARにテストクラスが入っていないことを確かめる
