#!/usr/bin/env bash
#
# sourceChecks が「正しい別解」を弾いていないかを検査する。
#
#   ./tools/check-source-alternatives.sh          … 検査する（3分ほど）
#   ./tools/check-source-alternatives.sh --list    … 意図した字面も含めて全件出す
#
# 模範解答を「意味を変えない別の書き方」へ1か所ずつ変形して提出し、
# **全テストケースを通るのに書き方の検査で落ちる**ものを挙げる。
# 出力が変わる変形（＝等価でない）はケースが落ちるので自動的に外れる。
#
# 2026-08-21に、5-3の応用問題が `n /= 10;` で落ちることが分かって作った。検査が `/\s*10` で
# 割り算の**字面**を固定していたためで、同じ形の取りこぼしが12問で見つかった。
#
# check-source-checks.sh は「ひな形が最初から満たしていないか」を、
# classify-source-checks.sh は「その構文が学習目標か」を見る。ここは3つ目の観点で、
# **学習目標であっても字面まで縛っていないか**を見る。
#
# 進捗ファイル(progress.json)は書き換えない（一時ディレクトリで動かすため）。
set -euo pipefail

cd "$(dirname "$0")/.."

PORT=8766

# shellcheck source=build.sh
source tools/build.sh
jq_build

mkdir -p build/tools
"$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 \
  -cp build/classes -d build/tools tools/CheckCount.java

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

# 先客がいると --exact-port で起動に失敗し、そのまま残っていたサーバを検査してしまう
if curl -fsS -o /dev/null "http://localhost:${PORT}/api/state" 2>/dev/null; then
  echo "ポート ${PORT} で別のサーバが動いています。" >&2
  echo "  止めてから実行してください: lsof -ti:${PORT} | xargs kill" >&2
  exit 1
fi

SANDBOX="$(mktemp -d)"
ln -s "$PWD/content" "$SANDBOX/content"
ln -s "$PWD/web" "$SANDBOX/web"
ln -s "$PWD/labs" "$SANDBOX/labs"
CLASSES_ABS="$PWD/build/classes"
( cd "$SANDBOX" && exec "$JQ_JAVA" -Dfile.encoding=UTF-8 -cp "$CLASSES_ABS" jq.App \
    --port "$PORT" --exact-port ) > "$SANDBOX/server.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "http://localhost:${PORT}/api/state" 2>/dev/null && break
  sleep 0.5
done
if ! curl -fsS -o /dev/null "http://localhost:${PORT}/api/state" 2>/dev/null; then
  echo "検査用サーバが起動しませんでした:" >&2
  tail -20 "$SANDBOX/server.log" >&2
  exit 1
fi

PATH="$(dirname "$JQ_JAVA"):$PATH" \
  python3 -u tools/check_source_alternatives.py \
    "build/classes:build/tools" "$PORT" "$@"
