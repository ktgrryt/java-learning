#!/usr/bin/env bash
#
# sourceChecks を1件ずつ3つの目的へ分類する（レビュー08-14の §8.7）。
#
#   ./tools/classify-source-checks.sh           … 集計を出す
#   ./tools/classify-source-checks.sh --list    … 全件を分類つきで出す
#   ./tools/classify-source-checks.sh --review  … 判断が要るものだけ出す
#
#   A 学習対象     … その構文・APIが到達目標か問題文で名指しされている
#   B 足場の見張り … ひな形が最初から満たしている（H-06の決着どおり、これは正常）
#   C 要判断       … AでもBでもない。偶発的な実装固定の疑いがある
#   D 禁止系       … minimum=0。ひな形が満たすのが正常なので分類の対象外
#
# 08-13のH-03が章ごとに判断し108件を外し、H-06が「ひな形が満たすのは足場の見張り」と決着させた。
# 残っていた「検査1件ずつの分類」を、到達目標のテキストと突き合わせて機械で当てる。
# Aの判定は語の一致なので取りこぼしがある。**Cは「外すべき」ではなく「読んで決める」**の意味。
#
# Bの判定は check-source-checks.sh と同じ jq.judge.CheckCount を通すので採点と一致する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/CheckCount.java

PATH="$(dirname "$JQ_JAVA"):$PATH" \
  python3 -u tools/classify_source_checks.py "build/classes:build/tools" "$@"
