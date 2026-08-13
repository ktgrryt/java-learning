# Quarkus project exercise

Quarkus Extension、MicroProfile Config、`@QuarkusTest`を同じMaven projectで完成させる演習です。
`mvn test`は通常のJUnitに加え、Quarkusを起動するHTTP境界テストと参照専用の受け入れテストを実行します。

編集対象は`pom.xml`、`GreetingService.java`、`application.properties`、
`GreetingResourceTest.java`です。`reference/`は採点時だけ使う模範解答です。
