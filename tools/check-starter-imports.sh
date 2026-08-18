#!/usr/bin/env bash
# 道具を初めて使う問題のひな形が、その道具の import と準備行を先に書いていないかを見る。
# 書いてあると、その道具を使う章を終えても「最初の書き方」を一度も打たないまま進める。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_starter_imports.py "$@"
