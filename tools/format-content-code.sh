#!/usr/bin/env bash
#
# content/*.json の教材コードのうち、1行に詰め込まれているものを整形して書き戻す。
#
#   ./tools/format-content-code.sh            … 整形して書き戻す
#   ./tools/format-content-code.sh --check    … 書き戻さず、対象件数と検査結果だけ出す
#
# 模範解答とひな形は「読んで覚える見本」なので、`int n=s.nextInt();` のような
# 詰まった書き方をJSONに残さない。章を書き足したあとに走らせる。
#
# 整形はアプリが表示に使うのと同じ jq.format.JavaSnippetFormatter に任せる。
# 書き戻す前に、整形前後でトークン列が一致すること（＝空白と改行しか変えていないこと）と、
# もう一度整形しても変わらないこと（冪等）を全件で確かめ、1件でも崩れたら何も書き戻さない。
#
# 空白の入れ方まで変わるので、走らせたあとは必ず ./tools/verify-solutions.sh を通すこと
# （模範解答とサンプルを実際にコンパイルして実行する）。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/FormatContentCode.java

PATH="$(dirname "$JQ_JAVA"):$PATH" \
  python3 -u tools/format_content_code.py "build/classes:build/tools" "$@"
