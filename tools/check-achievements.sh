#!/usr/bin/env bash
#
# 達成条件で解放されるスペシャルアイテムが、条件を満たしたときだけ現れるか確かめる。
# 本番と同じ ProgressStore を一時ファイルで動かすので、自分の進捗は汚さない。
#
#   ./tools/check-achievements.sh
#
# カフェ経済全体の試算は ./tools/simulate-cafe.sh の方。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/AchievementCheck.java
"$JQ_JAVA" -cp build/classes:build/tools AchievementCheck
