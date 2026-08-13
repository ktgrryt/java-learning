# Spring Boot runtime exercise

この演習は`mvn test`に加えて、作成したJARをランナーが選んだ動的ポートで実際に起動します。
正常API、入力不正、Actuator healthをHTTPで確認し、最後にサーバープロセスを停止します。

学習者が編集するのは`application.properties`だけです。`run-runtime-lab.sh`と
`RuntimeProbe.java`は採点手順を固定する参照専用ファイルです。
