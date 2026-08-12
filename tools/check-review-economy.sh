#!/usr/bin/env bash
#
# 「今日の1杯目」と、復習がカフェへ渡すもの（ブランド倍率・自動売上の枠・設備費割引）が
# 上限どおりに効くか確かめる。本番と同じ ProgressStore を一時ファイルで動かすので、
# 自分の進捗は汚さない。
#
#   ./tools/check-review-economy.sh
#
# カフェ経済全体の試算は ./tools/simulate-cafe.sh、
# アイテムの解放条件は ./tools/check-achievements.sh の方。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/ReviewEconomyCheck.java
"$JQ_JAVA" -cp build/classes:build/tools ReviewEconomyCheck
