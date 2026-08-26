#!/usr/bin/env bash
# ひな形の `return 0;` のような仮の値に、仮だと分かる注記と TODO が付いているかを見る。
# ひな形は単体でコンパイルできる必要があるため値そのものは消せない ―― 読み手に伝える。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_starter_placeholder.py "$@"
