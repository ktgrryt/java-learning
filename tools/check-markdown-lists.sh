#!/usr/bin/env bash
#
# リスト項目が2行以上に分かれていないかを見る。
# web/markdown.js はリスト項目を1行しか読まないので、続き行は左端の別の段落になる。
# 8-4（教材 7-4）の手順が別段落へ落ちていたのが発端で、教材に59件あった。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_markdown_lists.py "$@"
