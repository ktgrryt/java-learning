#!/usr/bin/env bash
# 解説の本文に書いた出力（`// 期待値` と、直後の出力ブロック）を実際に実行して照合する。
# `samples[].expected` は verify-solutions が見るが、本文の出力は誰も見ていなかった。
set -euo pipefail

cd "$(dirname "$0")/.."

# 実行に使うJDKは build.sh と同じ選び方にする（PATHの別のJDKを混ぜないため）。
# shellcheck source=build.sh
source tools/build.sh

PATH="$(dirname "$JQ_JAVAC"):$PATH" python3 tools/check_explanation_output.py "$@"
