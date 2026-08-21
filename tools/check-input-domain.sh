#!/usr/bin/env bash
# くり返しの回数を決める整数を「整数」とだけ紹介している問題を見る。
# 負を入れたときのふるまいが課題文から決まらず、テストケースにも無い状態を落とす。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_input_domain.py "$@"
