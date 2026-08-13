#!/usr/bin/env bash
# artifact検証器の形式別チェックと、XML外部エンティティ拒否を回帰検査する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/ArtifactValidatorCheck.java
"$JQ_JAVA" -cp build/classes:build/tools ArtifactValidatorCheck
