#!/usr/bin/env bash
# 静的配信がsymlinkやパス脱出でweb外のファイルを公開しないことを検査する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/StaticHandlerCheck.java
"$JQ_JAVA" -cp build/classes:build/tools jq.web.StaticHandlerCheck
