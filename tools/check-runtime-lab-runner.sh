#!/usr/bin/env bash
# runtime-labの隔離、実行protocol、timeout、環境不足の扱いを回帰検査する。
set -euo pipefail

cd "$(dirname "$0")/.."
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/RuntimeLabRunnerCheck.java
"$JQ_JAVA" -cp build/classes:build/tools RuntimeLabRunnerCheck
