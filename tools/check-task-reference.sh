#!/usr/bin/env bash
# 課題文とヒントが「上の問題」を位置で指していないか見る。
# 復習は1問だけを出すので（→ renderReviewTask）、指す先が画面に無い課題文になる。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_task_reference.py "$@"
