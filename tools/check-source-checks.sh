#!/usr/bin/env bash
#
# sourceChecks が「ひな形のままで満たされていないか」を、採点と同じ判定で数える。
#
#   ./tools/check-source-checks.sh                … 既存分（基準値）を超えたら失敗にする
#   ./tools/check-source-checks.sh --list          … 数えるだけ（終了コードは常に0）
#   ./tools/check-source-checks.sh --baseline 90   … 基準値を変える
#   ./tools/check-source-checks.sh --strict        … 1件でもあれば失敗にする
#
# verify-solutions.sh は「ひな形が全体として合格しないか」を見る。検査が複数ある問題では、
# そのうち1件が空振りしていても他の検査が落ちるので隠れてしまう。ここでは1件ずつ数える。
#
# サーバーを立てないので数秒で終わる。sourceChecks を足したり書き換えたら、
# 数分かかる verify-solutions.sh の前にこれを通すと早い。
#
# 判定は jq.judge.CheckCount 経由で jq.judge.SourceChecker.codeOnly を呼ぶので、
# 提出時の採点と一致する（Pythonで正規表現を再実装すると Java との差で食い違う）。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/CheckCount.java

PATH="$(dirname "$JQ_JAVA"):$PATH" \
  python3 -u tools/check_source_checks.py "build/classes:build/tools" "$@"
