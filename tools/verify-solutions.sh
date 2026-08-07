#!/usr/bin/env bash
#
# 全レッスンの模範解答を実際に提出して、全テストケースを通ることを確かめる。
# コンテンツを追加・修正したあとに走らせるための回帰チェック。
#
#   ./tools/verify-solutions.sh              … 一時サーバを自分で立てて検査する
#   ./tools/verify-solutions.sh --port 8123  … すでに動いているサーバを使う
#
# 進捗ファイル(progress.json)は書き換えない（一時ディレクトリで動かすため）。
#
set -euo pipefail

cd "$(dirname "$0")/.."

PORT=""
if [[ "${1:-}" == "--port" && -n "${2:-}" ]]; then
  PORT="$2"
fi

BUILD_DIR="build/classes"

# javac / java を同じJDKに固定してビルドする（run.sh と同じ仕組み）
# shellcheck source=build.sh
source tools/build.sh
jq_build

SANDBOX=""
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then kill "$SERVER_PID" 2>/dev/null || true; fi
  if [[ -n "$SANDBOX" ]]; then rm -rf "$SANDBOX"; fi
}
trap cleanup EXIT

if [[ -z "$PORT" ]]; then
  # 本物の progress.json を汚さないよう、content と web だけを貸した一時ディレクトリで動かす
  SANDBOX="$(mktemp -d)"
  ln -s "$PWD/content" "$SANDBOX/content"
  ln -s "$PWD/web" "$SANDBOX/web"
  PORT=8765
  ( cd "$SANDBOX" && exec "$JQ_JAVA" -cp "$OLDPWD/$BUILD_DIR" jq.App --port "$PORT" ) \
    > "$SANDBOX/server.log" 2>&1 &
  SERVER_PID=$!

  for _ in $(seq 1 60); do
    if curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then break; fi
    sleep 0.25
  done
  if ! curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then
    echo "サーバを起動できませんでした:" >&2
    cat "$SANDBOX/server.log" >&2
    exit 1
  fi
fi

exec python3 tools/verify_solutions.py "$PORT"
