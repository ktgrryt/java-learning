#!/usr/bin/env bash
# 模範解答が要求する道具（メソッド・クラス）が、ヒントを開かないと分からない状態に
# なっていないかを見る。ヒントは開くと報酬が減るので、そこにしか無い道具は「無い」のと同じ。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_hint_dependency.py "$@"
