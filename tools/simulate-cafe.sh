#!/usr/bin/env bash
# 本番と同じ ProgressStore で、content の必須問題すべてのカフェ経済を最後まで試算する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/CafeBalanceSimulation.java
"$JQ_JAVA" -cp build/classes:build/tools CafeBalanceSimulation .
