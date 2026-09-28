# JacksonでJSONとDTOを往復する

JDK 21以上とMavenが必要です。初回の `mvn test` は依存物を取得します。
OrderJson.javaを完成させてください。固定テストは往復変換、項目順序、必須項目・null・型・業務上の値・壊れたJSONを確認します。
この演習はHTTP通信より内側のJSON境界を対象にします。既存のHTTP演習とは別に検証します。
