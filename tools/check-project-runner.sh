#!/usr/bin/env bash
# Java/project runnerの実行隔離と、子プロセス回収を回帰検査する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/JavaRunnerCheck.java tools/ProjectRunnerCheck.java
"$JQ_JAVA" -cp build/classes:build/tools JavaRunnerCheck
"$JQ_JAVA" -cp build/classes:build/tools ProjectRunnerCheck
