#!/usr/bin/env bash
# 事前確認の版判定、必須・任意、ポート検査を回帰確認する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/PreflightRunnerCheck.java
"$JQ_JAVA" -cp build/classes:build/tools PreflightRunnerCheck
