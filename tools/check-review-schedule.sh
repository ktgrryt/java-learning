#!/usr/bin/env bash
#
# 忘却曲線の復習期限と、細かくした苦手度の目盛りを確かめる。
# 本番と同じ ProgressStore を一時ファイルで動かすので、自分の進捗は汚さない。
#
#   ./tools/check-review-schedule.sh
#
# 復習がカフェへ渡すものは ./tools/check-review-economy.sh の方。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/ReviewScheduleCheck.java
"$JQ_JAVA" -cp build/classes:build/tools ReviewScheduleCheck
