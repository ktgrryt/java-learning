# Jakarta EE 11 実サーバー演習（第46章 48-5の採点対象）

Jakarta EE 11のAPIが実サーバー上でどう振る舞うかを確かめる演習です。編集するのは
`src/main/java/cafe/api/UserResource.java` だけで、`reference/` が模範解答です。

`pom.xml`・`server.xml`・`jvm.options`・`RuntimeProbe.java` は変更しません。Feature Managerの
扱いは第49章（Open Liberty）で行います。ここではEE 11のAPIそのものに集中します。

## 何を確かめるか

`POST /api/users` へ次の本文を送ります。

```json
{"name":" Aki ","email":"aki@example.test","registeredAt":"2026-08-13T09:00:00Z"}
```

| 見るところ | 期待 | 使うEE 11の仕組み |
|---|---|---|
| status | 201 | Jakarta REST 4.0の`Response.status(CREATED)` |
| 名前・メール | 前後の空白を除いてJSONへ入る | JSON-B 3.0がrecordをgetter無しで変換する |
| 登録時刻 | `"registeredAt":"2026-08-13T09:00:00Z"` | `Instant`が既定でISO-8601になる |
| 空の名前・形式違反のメール | 400 | Bean Validation 3.1がrecordの構成要素の制約を見る |

## 手で動かす場合

```sh
JQ_LAB_PORT=9080 ./run-runtime-lab.sh
```

採点と同じ流れで、`mvn package` → Open Liberty 26の準備 → 動的portへ配備 → 実HTTP検証 →
停止まで進みます。手元で確認した所要は約19秒です（Libertyの実行時zipが`~/.m2`にある場合）。

## 分かったこと

親ディレクトリのREADMEでは「この環境にはアプリサーバーが無いため、デプロイ後の応答は未確認」と
していました。この演習で実際に配備して確認できたのは次の3点です。

- recordはgetterを書かなくてもJSON-Bが読み書きする（要求の本文も応答も）
- `Instant`は追加設定なしでISO-8601の文字列になる
- recordの構成要素へ付けた`@NotBlank`・`@Email`は、引数へ`@Valid`を付けるとHTTP 400になる

生成物（`target/` `runtime/`）はコミットしません。
