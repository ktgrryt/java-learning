# Spring Boot project exercise

画面で編集した6ファイルを一時プロジェクトへ反映し、`mvn test`で採点する演習です。
Controller・Service・外部設定・Web層テストを分けたまま、必要なStarterとDI、Validationを完成させます。

`src/test/java/example/greeting/RequirementsTest.java`は要件を固定する参照専用テストです。
学習者が追加する`GreetingControllerTest`と`GreetingServiceTest`も含め、すべてのテストが通る状態を目指します。
