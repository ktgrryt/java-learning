#!/usr/bin/env bash
# 到達目標がバッククォートで名指しした構文・APIが、その目標を測る問題に出てくるかを見る。
# check-objectives.sh は「紐づいているか」しか見ないので、目標だけが広い状態はここで捕まえる。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_objective_terms.py "$@"
