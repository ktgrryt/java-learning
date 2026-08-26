#!/usr/bin/env bash
# 課題文が「どんな入力が、どの順で与えられるか」を書いているかを見る。
# 数だけ書いた形（`3整数を読み`）と、入力に一言も触れない問題を落とす。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_task_input.py "$@"
