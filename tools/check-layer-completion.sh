#!/usr/bin/env bash
#
# 3層（概念／コード／実践）の数え方と、達成状態の永続化を確かめる。
# 自分の progress.json は使用しない（一時ディレクトリで動かす）。
#
# いちばん確かめたいのは「一度達成した層は、章へ問題が増えても記録が消えない」ことである。
# 導出だけにしていると、章を書き足した瞬間に過去の達成が未達成へ戻ってしまう。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/LayerCompletionCheck.java
"$JQ_JAVA" -cp build/classes:build/tools LayerCompletionCheck .
