#!/usr/bin/env bash
#
# Java Café を起動する。
#   ./run.sh              … ビルドして起動（http://localhost:8123）
#   ./run.sh --port 9000  … ポートを指定して起動
#   ./run.sh --no-open    … ブラウザを自動で開かない
#
# 使うJDKを選びたいときは JQ_JAVA_HOME を指定する:
#   JQ_JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./run.sh
#
set -euo pipefail

cd "$(dirname "$0")"

OPEN_BROWSER=1
JAVA_ARGS=()
PORT=8123

for arg in "$@"; do
  if [[ "$arg" == "--no-open" ]]; then
    OPEN_BROWSER=0
  else
    JAVA_ARGS+=("$arg")
  fi
done

for ((i = 0; i < ${#JAVA_ARGS[@]}; i++)); do
  if [[ "${JAVA_ARGS[$i]}" == "--port" && $((i + 1)) -lt ${#JAVA_ARGS[@]} ]]; then
    PORT="${JAVA_ARGS[$((i + 1))]}"
  fi
done

# アプリ（tools/make-app.sh で作る Java Café.app）から起動した分がまだ残っていると、
# ここで立てるサーバは別のポートにずれる。黙ってずれると混乱するので先に知らせる。
if curl -fsS --max-time 2 "http://localhost:${PORT}/" 2>/dev/null | grep -q 'Java Café'; then
  echo "注意: ポート ${PORT} ではすでに Java Café が動いています。"
  echo "      ターミナルから起動し直すなら、先にアプリを終了するか tools/launch.sh --stop してください。"
  echo ""
fi

# javac / java を同じJDKに固定してビルドする
# shellcheck source=tools/build.sh
source tools/build.sh
jq_build

# ブラウザを開くのはアプリ側（--open）に任せる。ここで "http://localhost:${PORT}" を
# 決め打ちで開くと、そのポートが埋まっていたときにアプリは +1 したポートに立つのに、
# ブラウザは埋めていた側（＝古いサーバ）を開いてしまう。
if [[ "$OPEN_BROWSER" == "1" ]]; then
  JAVA_ARGS+=("--open")
fi

exec "$JQ_JAVA" -Dfile.encoding=UTF-8 -cp build/classes jq.App ${JAVA_ARGS[@]+"${JAVA_ARGS[@]}"}
