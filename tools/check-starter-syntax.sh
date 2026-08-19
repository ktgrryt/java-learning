#!/usr/bin/env bash
# 初めて習う構文を、その構文を教える問題のひな形が先に書いていないかを見る。
# 書いてあると、その構文を教える章を終えても「外枠」を一度も打たないまま進める。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_starter_syntax.py "$@"
