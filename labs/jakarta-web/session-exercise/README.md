# セッションとCookie lab

実Open Libertyへ配備して、利用者ごとの状態の持ち方とCookieの属性を直します。
第23章の「章末演習：Webの状態管理」が採点に使います。

```
sh run-runtime-lab.sh
```

必要なもの: JDKとMaven。Open Liberty 26.0.0.8 はMavenが取得します。使うFeatureは
`servlet-6.1` の1つだけです。

## 何をするか

`src/main/java/cafe/web/CartServlet.java` を直します。かごをServletのフィールドに
持っているので、**全員が同じかごを見ます**。

| 検査 | 実サーバーで起きること |
|---|---|
| `session-continuity` | 同じブラウザで追加したものが次の要求にも残る（ひな形でも通ります） |
| `session-isolation` | **別のブラウザから他人のかごが見える** |
| `session-fixation` | セッションを使っていないので、ログインでIDが変わらない |
| `cookie-attributes` | `visitor` Cookieに`HttpOnly`も`SameSite`も付いていない |

## 「別の利用者」をどう作るか

Cookieの入れ物（`CookieManager`）を分けた**別々のHTTP client**を使います。
同じclientを使い回すと同じJSESSIONIDを送ってしまい、別の利用者を作れません。

いま持っているセッションIDは、応答からは読めません（`CookieHandler`が付けたCookieは
要求ヘッダに現れない）。入れ物から直接読んでいます。

## セッション固定攻撃をどう測るか

1. ログイン前のJSESSIONID（`s1`）を記録する
2. ログインして、新しいJSESSIONID（`s2`）を記録する
3. `s1`だけを提示する第三者が、ログイン後の状態に入れないことを確かめる

`s1` のまま使い続ける実装だと、攻撃者が先に取得させたIDでログイン後のセッションへ入れます。
`changeSessionId()` なら、かごの中身を保ったままIDだけ作り直せます。

## Cookieの属性

- `HttpOnly` … JavaScriptから読めなくする（盗まれにくくする）
- `SameSite=Lax` … 他サイトからの送信を止める（CSRF対策）
- `Secure` … HTTPSでだけ送る。**この演習はHTTPなので付けません**（付けると届かなくなります）。
  本番では必ず付けます

`Cookie.setAttribute("SameSite", "Lax")` はServlet 6.0以降で使えます。

## URLの形

`@WebServlet("/cart/*")` なので、パスは次のとおりです。

| 操作 | URL |
|---|---|
| かごを見る | `GET /cart` |
| かごへ追加 | `POST /cart?item=X` |
| ログイン | `POST /cart/login?user=X` |
| ログアウト | `POST /cart/logout` |

応答の形（`user=<名前> cart=[品名, 品名]`）とマッピングは採点の足場が読むので変えないでください。
