#!/usr/bin/env bash
#
# 「押したらすぐ遊べる」用の起動スクリプト。run.sh との違いは2つ:
#
#   1. サーバをバックグラウンドに逃がして、自分はブラウザを開いたら終わる
#      （Dock / Finder から起動したときにターミナルを開いたままにしなくていい）
#   2. すでに動いているなら二重に起動せず、ブラウザを開くだけにする
#
#   tools/launch.sh          … 起動してブラウザを開く
#   tools/launch.sh --stop   … 動いているサーバを止める
#   tools/launch.sh --status … 動いているか調べる
#
# tools/make-app.sh が作る「Java Quest.app」は、起動時にこれを JQ_GUI=1 で呼び、
# 終了（右クリック →「終了」）のときに --stop を呼ぶ。
# GUI から呼ばれたときは、標準エラーを読む人がいないのでエラーをダイアログで出す。
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

PORT="${JQ_PORT:-8123}"
URL="http://localhost:${PORT}"
LOG="$ROOT/build/server.log"
PIDFILE="$ROOT/build/server.pid"
READY_TIMEOUT=40   # 0.25秒 × 40 = 最大10秒待つ

# ---- エラーの伝え方 --------------------------------------------------------
# GUI起動だとターミナルが無いので、echo だけでは何も伝わらない
die() {
  echo "$1" >&2
  if [[ "${JQ_GUI:-0}" == "1" ]]; then
    osascript \
      -e 'on run {msg}' \
      -e 'activate' \
      -e 'display dialog msg with title "Java Quest" buttons {"OK"} default button 1 with icon stop' \
      -e 'end run' \
      -- "$1" >/dev/null 2>&1 || true
  fi
  exit 1
}

# ログの中身を添えて終わる。ポートの取り合いはよくあるので専用の案内を出す
die_with_log() {
  if grep -q 'Address already in use' "$LOG" 2>/dev/null; then
    die "$1"$'\n\n'"ポート ${PORT} を別のアプリが使っています。"$'\n'"JQ_PORT=9000 tools/launch.sh のように別のポートを指定すると起動できます。"
  fi
  die "$1"$'\n\n'"$(tail -n 12 "$LOG")"$'\n\n'"詳しくは build/server.log を見てください。"
}

# ---- 動いているか調べる ----------------------------------------------------
# ポートが開いているだけでは足りない。別のアプリが同じポートを使っていることも
# あるので、返ってきた画面が Java Quest かどうかまで確かめる。
is_running() {
  curl -fsS --max-time 2 "$URL/" 2>/dev/null | grep -q 'Java Quest'
}

stop_server() {
  local stopped=0 pid
  if [[ -f "$PIDFILE" ]]; then
    pid="$(cat "$PIDFILE")"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null && stopped=1
    fi
    rm -f "$PIDFILE"
  fi
  # PIDファイルが無い / 古いときの保険。ここまで来たら止める相手を特定できていないので、
  # Java Quest のサーバを全部止める（run.sh で別ポートに立てた分も一緒に止まる）
  if [[ "$stopped" == "0" ]] && pkill -f 'jq\.App' 2>/dev/null; then
    stopped=1
  fi
  if [[ "$stopped" == "1" ]]; then
    echo "Java Quest を止めました。"
  else
    echo "Java Quest は動いていません。"
  fi
}

case "${1:-}" in
  --stop)
    stop_server
    exit 0
    ;;
  --status)
    if is_running; then
      echo "動いています: $URL"
    else
      echo "動いていません。"
      exit 1
    fi
    exit 0
    ;;
  "") ;;
  *) die "使えないオプションです: $1（--stop / --status のどれかです）" ;;
esac

# ---- すでに動いているならブラウザを開くだけ --------------------------------
if is_running; then
  open "$URL"
  exit 0
fi

mkdir -p build
: > "$LOG"

# ---- ビルド ----------------------------------------------------------------
# tools/build.sh はJDKが見つからないと exit する。source すると自分ごと落ちて
# しまいメッセージを拾えないので、サブシェルに閉じ込めて出力を受け取る。
# 使うJDKもそのサブシェルの中で決まるので、ファイル経由で受け取る。
JAVA_BIN_FILE="$ROOT/build/.java-bin"
if ! (
      source tools/build.sh
      jq_build
      printf '%s' "$JQ_JAVA" > "$JAVA_BIN_FILE"
     ) >>"$LOG" 2>&1; then
  die_with_log "ビルドできませんでした。"
fi
JAVA_BIN="$(cat "$JAVA_BIN_FILE")"

# ---- サーバをバックグラウンドで起動 ----------------------------------------
# --exact-port を付けている。ポートが埋まっていたら黙って隣にずれるより、
# 「8123 が誰かに使われている」と言われた方が原因に手が届く。
nohup "$JAVA_BIN" -Dfile.encoding=UTF-8 -cp build/classes jq.App \
  --port "$PORT" --exact-port >>"$LOG" 2>&1 &
server_pid=$!
echo "$server_pid" > "$PIDFILE"

for _ in $(seq 1 "$READY_TIMEOUT"); do
  if is_running; then
    open "$URL"
    echo "Java Quest を起動しました: $URL"
    exit 0
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    rm -f "$PIDFILE"
    die_with_log "サーバが起動できませんでした。"
  fi
  sleep 0.25
done

die_with_log "サーバの応答がありません（${URL}）。"
