#!/usr/bin/env bash
# 採点の誤合格を再発させないための小さな回帰検査。
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/build.sh
jq_build
mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 -cp build/classes -d build/tools \
  tools/SourceFieldCheck.java tools/ReportStructureCheck.java \
  labs/logging-investigation/app/src/test/java/cafe/logging/ReportStructure.java
"$JQ_JAVA" -cp build/classes:build/tools SourceFieldCheck
"$JQ_JAVA" -cp build/classes:build/tools cafe.logging.ReportStructureCheck
