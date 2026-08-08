#!/usr/bin/env bash
#
# 全レッスンの模範解答を実際に提出して、全テストケースを通ることを確かめる。
# コンテンツを追加・修正したあとに走らせるための回帰チェック。
#
#   ./tools/verify-solutions.sh                 … 一時サーバを自分で立てて検査する
#   ./tools/verify-solutions.sh --port 8123     … すでに動いているサーバを使う
#   ./tools/verify-solutions.sh --only 21       … 第21章だけ検査する
#   ./tools/verify-solutions.sh --only 21-3 22  … レッスン21-3と第22章だけ
#
# --only は章を1つ書いている間の確認用（全部で数分かかるので）。
# コンテンツを直し終えたら、必ず --only なしで全体を通してください。
#
# 進捗ファイル(progress.json)は書き換えない（一時ディレクトリで動かすため）。
#
set -euo pipefail

cd "$(dirname "$0")/.."

PORT=""
ONLY=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      PORT="${2:-}"
      if [[ -z "$PORT" ]]; then echo "--port にはポート番号が必要です" >&2; exit 1; fi
      shift 2
      ;;
    --only)
      shift
      while [[ $# -gt 0 && "$1" != --* ]]; do ONLY+=("$1"); shift; done
      if [[ ${#ONLY[@]} -eq 0 ]]; then
        echo "--only にはレッスンIDの先頭が必要です（例: --only 21）" >&2; exit 1
      fi
      ;;
    *)
      echo "知らない引数です: $1" >&2
      exit 1
      ;;
  esac
done

BUILD_DIR="build/classes"

# javac / java を同じJDKに固定してビルドする（run.sh と同じ仕組み）
# shellcheck source=build.sh
source tools/build.sh
jq_build

SANDBOX=""
SERVER_PID=""

# 検査用サーバと一時ディレクトリの後片付け。
# 最後を `exec python3` にすると、シェルが置き換わってこの trap が動かず、
# サーバと一時ディレクトリが毎回リークする（そして次回の検査が、残った
# 古いサーバに向いてしまう）。だから exec は使わない。
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    # 素直に終わらない場合に備えて、少し待ってから強制終了する
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

if [[ -z "$PORT" ]]; then
  PORT=8765

  # 前回の検査で残ったサーバや別の作業用サーバが同じポートにいると、
  # 古いクラスのまま検査してしまい「すべて合格」が嘘になる。先に潰しておく。
  if curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then
    echo "ポート ${PORT} で別のサーバが動いています。" >&2
    echo "検査が古いサーバに向いてしまうので、そちらを止めてから実行してください。" >&2
    echo "  lsof -nP -iTCP:${PORT} -sTCP:LISTEN" >&2
    exit 1
  fi

  # 本物の progress.json を汚さないよう、content と web だけを貸した一時ディレクトリで動かす
  SANDBOX="$(mktemp -d)"
  ln -s "$PWD/content" "$SANDBOX/content"
  ln -s "$PWD/web" "$SANDBOX/web"
  # --exact-port … 指定ポートが埋まっていても勝手にずらさせない。
  # ずらされると、この下の curl が「残っていた別のサーバ」に当たって
  # 検査がそちらに向いてしまう（それで合格しても意味がない）。
  CLASSES_ABS="$PWD/$BUILD_DIR"
  ( cd "$SANDBOX" && exec "$JQ_JAVA" -cp "$CLASSES_ABS" jq.App \
      --port "$PORT" --exact-port ) \
    > "$SANDBOX/server.log" 2>&1 &
  SERVER_PID=$!

  for _ in $(seq 1 60); do
    # サーバが死んでいたら待つ意味がない（--exact-port の失敗はここで即分かる）
    kill -0 "$SERVER_PID" 2>/dev/null || break
    if curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then break; fi
    sleep 0.25
  done
  # 起動できていないのに応答がある = 別のサーバが答えている。それも失敗として扱う
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "検査用サーバが起動できませんでした:" >&2
    cat "$SANDBOX/server.log" >&2
    exit 1
  fi
  if ! curl -s -o /dev/null "http://localhost:${PORT}/api/state"; then
    echo "サーバを起動できませんでした:" >&2
    cat "$SANDBOX/server.log" >&2
    exit 1
  fi
fi

# exec は使わない。使うとシェルが置き換わって trap cleanup が動かず、
# 検査用サーバと一時ディレクトリが毎回リークする。
# -u … 出力先がファイルやパイプでも1行ずつ流す（長い検査中に無言にならないように）
# ${ONLY[@]+...} … 空配列を set -u のもとで展開してもエラーにしない書き方（bash 3.2 対策）
RC=0
python3 -u tools/verify_solutions.py "$PORT" ${ONLY[@]+"${ONLY[@]}"} || RC=$?
exit "$RC"
