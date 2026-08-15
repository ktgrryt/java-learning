# REST APIの認可 lab

実Open Libertyへ配備して、「誰が呼べるか」と「何を返すか」を直します。
Web・Jakarta EE編『REST APIとセキュリティ』の「章末演習：REST API」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDKとMaven。Feature は `restfulWS-4.0`・`cdi-4.1`・`jsonb-3.0`・`appSecurity-6.0` です。

## 何をするか

`src/main/java/cafe/api/OrderResource.java` を直します。いまは**誰でも全部できます**。

| 検査 | 実サーバーで起きること |
|---|---|
| `rest-public` | 生存確認は資格情報なしで200（ここは公開のまま。ひな形でも通ります） |
| `rest-unauthenticated` | **資格情報なしで一覧が全部見える**（社内メモつきで） |
| `rest-no-leak` | 内部の形をそのまま返しているので`internalNote`と`customer`が出ていく |
| `rest-forbidden` | staffだけの利用者が削除できてしまう |

## 使える利用者

`CafeIdentityStore.java`（参照専用）に固定で入っています。

| 利用者 | パスワード | 役割 |
|---|---|---|
| `aki` | `aki-pass` | staff |
| `mgr` | `mgr-pass` | staff, manager |
| `bob` | `bob-pass` | （役割なし） |

本番でパスワードを平文で持つことはありません（保存方式はWeb・Jakarta EE編『Jakarta EE 11アップデート』で扱います）。
ここは認可の練習に集中するための固定データです。

## 401と403は違う

- **401 Unauthorized** … あなたが誰か分からない。資格情報を出し直せば通るかもしれない
- **403 Forbidden** … あなただと分かったが、権限が無い。出し直しても通らない

この区別はコンテナが付けます。ただし**宣言していなければどちらも起きません**。
`@RolesAllowed`が無いメソッドは公開です。「認証しているはず」という前提は、
コードのどこにも書かれていません。

## 内部の形をそのまま返さない

`Order`には社内メモ（`internalNote`）が入っています。内部の形をそのままJSONへ返すと、
こういう項目が一緒に出ていきます。外向きのrecordへ移し替えれば、
**内部にフィールドが増えても勝手に外へ出ていきません**。
「返す項目を選ぶ」のではなく「返す形を別に持つ」のが要点です。

## 参照専用のファイル

`ApiApplication.java`（Basic認証と役割の宣言）・`CafeIdentityStore.java`（利用者）・
`Order.java`・`OrderRepository.java`・`pom.xml`・`server.xml` は編集しません。
パスも採点の足場が使うので変えないでください。
