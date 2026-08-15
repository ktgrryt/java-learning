# Bean Validation lab

実Open Libertyへ配備して、入力の検査と400の返し方を直します。
Web・Jakarta EE編『Validation・テスト・本番運用』の「章末演習：安全なAPI」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDKとMaven。Feature は `restfulWS-4.0`・`cdi-4.1`・`jsonb-3.0`・`validation-3.1` です。

## 何をするか

編集するのは3ファイルです。「宣言する → 有効にする → 返す」の3段が揃わないと守れません。

| ファイル | やること |
|---|---|
| `OrderRequest.java` | 制約を宣言する（項目ごと＋項目間） |
| `OrderResource.java` | 宣言した制約を**有効にする**（1語の注釈） |
| `ValidationErrorMapper.java` | 違反を「直せる応答」へ変える |

| 検査 | 実サーバーで起きること |
|---|---|
| `validation-accepts` | 正しい注文は201（ひな形でも通ります） |
| `validation-rejects` | **空白だけの品名・数量0・合計0が201で通ってしまう** |
| `validation-cross-field` | クーポンありで1000円未満の組み合わせが通ってしまう |
| `validation-error-body` | 400の本文から、どの項目が悪いのか分からない |

## いちばん危ないのは「宣言しただけ」の状態

`@NotBlank` を書いても、Resourceの引数に `@Valid` が無ければ**1つも動きません**。
コードを読むと守られているように見えるので、実際に不正な入力を送るまで気づけません。
この lab がまず測るのはそこです。

## 応答に何を入れて、何を入れないか

`getPropertyPath()` は `create.arg0.quantity` のように**メソッド名と引数の位置**まで含みます。
そのまま返すと実装の形が外から見えるので、末尾の項目名だけを取ります。
例外の型名やスタックトレースも入れません（攻撃の手がかりになります）。

返す形は次のとおりです。項目名の**昇順**に並べます（順番が毎回変わると、
呼び出し側は応答を当てにしたテストを書けません）。

```json
{"errors":[{"field":"item","message":"..."},{"field":"quantity","message":"..."}]}
```

## 項目間のルール

「クーポンを使うなら1000円以上」は、`couponCode` だけを見ても `totalYen` だけを見ても
判定できません。`@AssertTrue` を付けた boolean のメソッドにすれば、項目間のルールも
同じ仕組みで扱えます。メソッド名は `isCouponUsable()` にしてください
（項目名が `couponUsable` になり、採点がそれを見ます）。

メッセージの文言は自由です。採点は項目名とステータスコードだけを見ます。

`ApiApplication.java`・`OrderStore.java`・`pom.xml`・`server.xml` は参照専用です。
