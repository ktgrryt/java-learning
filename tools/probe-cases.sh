#!/usr/bin/env bash
#
# テストケースを足すための下調べ。候補の入力を模範解答へ流して、実際の出力を見せる。
#
#   ./tools/probe-cases.sh spec.json
#
# spec.json は {"lesson","task","label","stdin"} の配列（tools/probe_cases.py 参照）。
# 期待する出力を手計算すると間違えるので、入力だけを決めて出力は模範解答に出させる。
# ただし出てきた出力が問題文どおりかは必ず目で確かめること。
#
# 進捗ファイル(progress.json)は書き換えない（一時ディレクトリで動かすため）。
#
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $# -lt 1 ]]; then
  echo "使い方: ./tools/probe-cases.sh spec.json" >&2
  exit 1
fi
SPEC="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# shellcheck source=build.sh
source tools/build.sh
jq_build

PORT=8767
SANDBOX=""
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [[ -n "$SANDBOX" ]]; then rm -rf "$SANDBOX"; fi
}
trap cleanup EXIT INT TERM

if curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then
  echo "ポート ${PORT} で別のサーバが動いています。止めてから実行してください。" >&2
  exit 1
fi

# 本物の progress.json を汚さないよう、content と web だけを貸した一時ディレクトリで動かす
SANDBOX="$(mktemp -d)"
ln -s "$PWD/content" "$SANDBOX/content"
ln -s "$PWD/web" "$SANDBOX/web"
CLASSES_ABS="$PWD/build/classes"
( cd "$SANDBOX" && exec "$JQ_JAVA" -cp "$CLASSES_ABS" jq.App --port "$PORT" --exact-port ) \
  > "$SANDBOX/server.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 60); do
  kill -0 "$SERVER_PID" 2>/dev/null || break
  if curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then break; fi
  sleep 0.25
done
if ! curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then
  echo "下調べ用サーバを起動できませんでした:" >&2
  cat "$SANDBOX/server.log" >&2
  exit 1
fi

RC=0
python3 -u tools/probe_cases.py "$PORT" "$SPEC" || RC=$?
exit "$RC"
