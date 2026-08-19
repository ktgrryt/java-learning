#!/usr/bin/env bash
# コードブロックの桁揃えが、画面（ブラウザ）でも揃っているかを見る。
# 等幅でも日本語は半角2文字ぶんにならない（環境により1.66〜1.82）ので、
# 端末やエディタで揃えた図はアプリでずれる。書いた本人の画面では気づけない。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_block_alignment.py "$@"
