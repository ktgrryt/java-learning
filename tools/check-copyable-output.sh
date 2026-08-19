#!/usr/bin/env bash
# 計算せずに入力を写すだけで通る問題（`単価=合計÷個数` のように結果が入力へ戻るもの）を見る。
# 通ってしまうと、その問題は学習内容ではなく転記を測っていることになる。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_copyable_output.py "$@"
