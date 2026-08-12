#!/usr/bin/env bash
#
# 初回オンボーディングの判定・保存・旧セーブ互換を、一時ファイルで確かめる。
# 自分の progress.json は使用しない。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/OnboardingCheck.java
"$JQ_JAVA" -cp build/classes:build/tools OnboardingCheck
