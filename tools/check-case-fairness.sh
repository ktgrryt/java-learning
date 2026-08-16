#!/usr/bin/env bash
# 隠しケースだけが求めている固定文言と、番号だけのケースラベルを探す。
# 学習者が読めるのは 問題文・ひな形・表示ケース だけなので、そこに無い文言は当てられない。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 tools/check_case_fairness.py "$@"
