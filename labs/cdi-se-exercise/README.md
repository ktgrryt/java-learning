# CDIを実際に動かす

JDK 21以上とMavenが必要です。初回の `mvn test` はMaven Centralから依存物を取得します。
`src/main/java/example/Workshop.java` のTODOを完成させ、`mvn test` を実行します。
CDI SEのWeldがBeanを作ります。MiniDiや自作EventBusは使いません。
テストはSMSの候補を残したままMailのProducerを選ぶことと、同期イベントをObserverへ届けることを確認します。
referenceは模範解答で、提出用の環境へはコピーされません。

仕様参考: https://docs.jboss.org/weld/reference/6.0.4.Final/en-US/html/
