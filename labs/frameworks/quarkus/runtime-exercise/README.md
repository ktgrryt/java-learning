# Quarkus JVM runtime exercise

`mvn package`で`target/quarkus-app`を作り、JVMモードで実際に起動する演習です。
固定scriptが動的localhost portを渡し、REST、Validation、SmallRye Health、停止をHTTPで検証します。

編集対象は`GreetingResource.java`、`GreetingReadiness.java`、`application.properties`です。
Native Imageはこの必須演習に含めず、隣の`native-exercise`へ分離しています。
