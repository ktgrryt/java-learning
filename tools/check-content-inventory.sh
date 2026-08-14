#!/usr/bin/env bash
#
# content/*.json から省略可能なキーが黙って消えていないかを確かめる。
#
#   ./tools/check-content-inventory.sh            … スナップショットと比べる
#   ./tools/check-content-inventory.sh --update   … スナップショットを書き直す
#
# `ContentLoader` は必須のキーが欠けると例外で止まる。しかし省略可能なキーは、
# 消えても何も起きない。`sourceChecks` を丸ごと落とした章は、模範解答検証も
# ひな形検証も通ってしまう（検査が減っただけで、残った検査は正しく動くため）。
#
# 実際に34問から68件の `sourceChecks` が消えた。原因は、章のJSONを生成スクリプトで
# 作り直したときに、出力側が並べていないキーが落ちたことである。`type` のような
# 必須キーは `ContentLoader` が止めたが、`sourceChecks` は黙って消えた。
#
# ここではレッスンと問題ごとの**個数だけ**を tools/content-inventory.json に記録し、
# 減っていたら失敗にする。増えるのは通常の加筆なので何も言わない。中身は見ないので、
# 問題文や模範解答の書き換えは自由にできる。
#
# サーバーもJDKも使わないので1秒で終わる。章のJSONを書き換えたら通すこと。
# 意図して減らしたときは --update で書き直し、差分でレビューする。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 -u tools/check_content_inventory.py "$@"
