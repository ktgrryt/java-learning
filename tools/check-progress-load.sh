#!/usr/bin/env bash
#
# 進捗ファイルが素直に読めないときの振る舞いを確かめる。
#
# 守りたいのは「読めなかったからといって記録を捨てない」こと。
#   ・JSONとして読めない      → 退避して作り直す（控えは上書きしない）
#   ・1件だけ形が違う          → その1件だけ飛ばし、残りは全部残す
#   ・取り込みで落ちた（不具合）→ ファイルに触らず起動を諦める（LoadFailedException）
# 3つめはデータからは起こせないので、ここでは1つめと2つめを見る。
#
# 自分の progress.json は使用しない（一時ディレクトリで動かす）。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/ProgressLoadCheck.java
"$JQ_JAVA" -cp build/classes:build/tools ProgressLoadCheck
