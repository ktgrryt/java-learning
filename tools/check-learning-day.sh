#!/usr/bin/env bash
#
# 1日の区切り（午前4時）を確かめる。
#
#   ./tools/check-learning-day.sh
#
# 連続学習日数・復習の期限・その日の達成条件・獲得の履歴は、すべて同じ境目を使う。
# ずれても**日付が変わる瞬間しか症状が出ない**ので、時計を動かさずに測れる形で見張る。
# 本番と同じ ProgressStore を一時ファイルで動かすので、自分の進捗は汚さない。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/LearningDayCheck.java
"$JQ_JAVA" -cp build/classes:build/tools LearningDayCheck

# 新しい日付判定が区切りを通らずに書かれるのを止める。
# `LocalDate.now()` を直に呼ぶと、そこだけ0時で切り替わって「今日」が食い違う
# （症状は深夜だけ出るので、画面を触る検査でも回帰でも出ない）。
echo ""
STRAY="$(grep -rn 'LocalDate\.now()' src/main/java \
  --exclude=LearningDay.java || true)"
if [[ -n "$STRAY" ]]; then
  echo "LocalDate.now() が残っています（LearningDay.today() を使ってください）:" >&2
  echo "$STRAY" >&2
  exit 1
fi
echo "src/main/java に LocalDate.now() の直呼びはありません（区切りは LearningDay だけが持つ）。"
