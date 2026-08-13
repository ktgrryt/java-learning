#!/usr/bin/env bash
# 任意発展問題が通常進捗を妨げないことを回帰検査する。
set -euo pipefail

cd "$(dirname "$0")/.."
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/OptionalTaskCheck.java
"$JQ_JAVA" -cp build/classes:build/tools OptionalTaskCheck
