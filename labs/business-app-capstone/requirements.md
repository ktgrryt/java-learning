# 変更要求：注文キャンセル

利用者が、自分の未処理注文を理由付きでキャンセルできるようにします。

## API契約

教材内では、`OrderController.cancel(orderId, request)`の戻り値でHTTP相当の結果を表します。

| 条件 | status | errorCode |
|---|---:|---|
| 本人・NEW・有効な入力 | 204 | 空文字 |
| 同じ冪等キーの再送 | 204 | 空文字 |
| 理由が空、100文字超、冪等キーが空 | 400 | `invalid_request` |
| 注文が無い | 404 | `order_not_found` |
| 他人の注文 | 403 | `forbidden` |
| PAID | 409 | `order_not_cancellable` |
| 予期しない保存失敗 | 500 | `internal_error` |

検査順は、入力 → 注文の存在 → 所有者 → 状態です。

## 成功時の更新

- 注文状態を`NEW`から`CANCELLED`へ変える
- 前後の空白を除いた理由を保存する
- `Clock`から得た時刻を`cancelledAt`へ保存する
- versionを1増やす
- `OrderCancelled`イベントをoutboxへ1件追加する
- 監査ログへ`requestId`、`orderId`、`actorId`、結果を1件記録する
- 理由全文、トークン、Cookieは監査ログへ入れない

注文更新とoutbox追加は、同じDBトランザクションで成功または失敗しなければなりません。この教材では
Repositoryの一つのメソッドで簡略化します。

## 再送

同じ冪等キーを再び受け取った場合は、最初の結果を返し、注文保存、outbox追加、監査ログを繰り返しません。
保存処理に失敗した依頼は処理済みにしません。

## DB変更

既存行と旧版アプリを壊さないexpand段階として、次を追加します。

- `orders.cancel_reason VARCHAR(100)`（最初はNULL許可）
- `orders.cancelled_at TIMESTAMP WITH TIME ZONE`（最初はNULL許可）
- `order_outbox`テーブル
- `event_id`の主キー

## 引き継ぎ

`PR.md`に、変更理由、API/DBへの影響、実行したテスト、配備順、監視項目、切り戻し方針を残します。
