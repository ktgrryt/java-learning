# 依頼: 配備後の一覧遅延とキャンセル失敗を立て直す

## 状況

2026-08-12 09:12:58Z に `orders-2.4.0` を配備しました。そのあと2つの問題が報告されています。

1. 注文一覧（`GET /orders`）の応答が 40ms 前後から 1.2 秒前後へ悪化した。
2. 注文キャンセル（`POST /orders/{id}/cancel`）が失敗する報告が続いた。在庫サービスは
   09:21 に自力で回復している。

さらに、配備の入れ替え中に `GET /readyz` が `ready=true` を返し続けていたため、
落ちた依存先へ要求が流れていました。

事実は `app/incident/orders.log` と `app/incident/deployments.txt` にあります。
どちらも参照専用です。

## 受け入れ条件

`./run-tests.sh` が `tests=13 passed=13 failed=0` になること。内訳は次のとおりです。

### 一覧API（`OrderListService`）

- API契約（`app/api/openapi-v1.json`）どおり、**新しい注文から順に**返す。
- 店舗名を注文1件ごとに引かず、必要な店舗を**まとめて1回**で引く。
  問い合わせは「注文1回＋店舗1回」の2回になる。
- 注文が0件のときは店舗の問い合わせを投げない。

### 在庫解放（`InventoryReleaseService`）

- 一時障害は**最大3回**まで試す。
- 試行の合間だけ待ち、待ち時間は 200ms から**毎回2倍**にする。
- 打ち切ったときの `errorCode` は短い名前だけにする。下流が返す内部情報
  （ホスト名・資格情報）を応答へ透かさない。
- 恒久障害は再試行しない。
- 同じ冪等キーで一度成功していたら、下流へ送り直さない。

### readiness（`ReadinessProbe`）

- 依存先ごとに `up` / `down` を返す。
- 1つでも落ちていれば `ready=false` にする。
- 依存先の確認が例外を投げても、この確認自体は例外を投げない。

### DB移行（`db/migration/V4__add_cancel_audit.sql`）

- expand（足すだけ）の段階として書く。この移行では `DROP` しない。
- `orders` へ `cancel_requested_at` を足す。既存行にはNULLが入るので `NOT NULL` にしない。
- `order_cancel_audit` を作る。`order_id` と `idempotency_key` の組を一意にし、
  `attempts` に試行回数を残す。

### 引き継ぎ（`RUNBOOK.md` / `ADR.md`）

文章の巧みさは採点しません。採点するのは、**必要な事実が、それを必要とする節に、
確認できる形で書かれているか**です。

- RUNBOOK に 検知・確認・緩和・切り戻し・連絡 の5節を残す。**事実は節ごとに置く。**
  - 「検知」に、最初にエラーが出た時刻とエラー名
  - 「確認」に、遅くなった要求の `queries=` の値と、その直前の配備の版
  - 「切り戻し」に、戻す先の版（`deployments.txt` で確認できます）と、
    **数と単位のある**判断の目安（「様子を見て」では次の人が判断できません）
- ADR に 文脈・決定・却下した案・影響 の4節を残す。
  - 「文脈」に、きっかけになった配備の版と、測った値
  - 「却下した案」に、選ばなかった案を**2つ以上**、行頭 `- ` の箇条書きで並べる。
    案の名前だけで終わらせず、選ばなかった理由も同じ項目へ書く
  - 「影響」に、**移行**と**監視**への影響
- どちらの文書も、見出しだけ残して中身を空にしたり、他の節を写して埋めたりできません。

## 変更できるもの

次の6ファイルだけです。

- `app/src/main/java/cafe/ops/OrderListService.java`
- `app/src/main/java/cafe/ops/InventoryReleaseService.java`
- `app/src/main/java/cafe/ops/ReadinessProbe.java`
- `app/db/migration/V4__add_cancel_audit.sql`
- `app/RUNBOOK.md`
- `app/ADR.md`

テスト、incidentログ、API契約、V1〜V3の移行は変更できません。
