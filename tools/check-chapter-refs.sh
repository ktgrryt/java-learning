#!/usr/bin/env bash
#
# 章参照（「第NN章」）が、学習者に正しい番号で見えるかを確かめる。
#
#   ./tools/check-chapter-refs.sh          … 検査する
#   ./tools/check-chapter-refs.sh --list   … 全参照を一覧する
#
# この教材には章番号が3つある。`content/chNN-*.json` の**内部番号**、画面に出る
# **編内番号**（編ごとに1から振り直す）、編内番号へ移行する前の**旧通し番号**である。
#
# `content/*.json` の本文は内部番号で書き、`web/app.js` の `localizeChapterReferences`
# が編内番号へ読み替える（別の編なら編名も前に付ける）。だから本文は並び替えに自動で
# 追従する。読み替えが効かないのは次の3つで、どれも画面を見ないと気づけない。
#
#   1. 参照先の `chNN` が無い       … 書いた数字がそのまま出る
#   2. `esc` で表示するフィールド    … 読み替えを通らない
#   3. `labs/**/*.md`               … READMEはファイルとして読まれるので誰も読み替えない
#
# 実際に labs のREADMEは30ファイルが旧通し番号と内部番号を混在させていた。
# `docs/` は保守者向けで、ファイルを探すために内部番号を `chNN` と書くので対象外。
#
# サーバーもJDKも使わないので1秒で終わる。章参照やlabのREADMEを直したら通すこと。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 -u tools/check_chapter_refs.py "$@"
