#!/usr/bin/env bash
# まだ習っていない用語を、教える章への案内なしに解説やクイズで使っていないかを見る。
# 「メソッドへ切り出して return」が、メソッドを学ぶ2章前に出ていたのが発端。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_forward_terms.py "$@"
