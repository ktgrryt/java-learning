#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
base_url="http://127.0.0.1:$port"
runtime_dir="runtime"
server_cmd="target/liberty/wlp/bin/server"
server_pid=""
mkdir -p "$runtime_dir/classes"

stop_server() {
  if [ -z "$server_pid" ]; then return; fi
  "$server_cmd" stop defaultServer >/dev/null 2>&1 || kill "$server_pid" 2>/dev/null || true
  attempt=0
  while [ "$attempt" -lt 10 ] && kill -0 "$server_pid" 2>/dev/null; do
    attempt=$((attempt + 1))
    sleep 1
  done
  if kill -0 "$server_pid" 2>/dev/null; then kill "$server_pid" 2>/dev/null || true; fi
  wait "$server_pid" 2>/dev/null || true
}

cleanup() { stop_server; }
trap cleanup EXIT INT TERM

fail_all() {
  for id in session-continuity session-isolation session-fixation cookie-attributes; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

# 学習者のコードがそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
show() {
  [ -s "$1" ] || return 0
  sed 's/^JQ_CHECK/JQ-CHECK/' "$1"
}

if ! mvn -q package >"$runtime_dir/build.log" 2>&1; then
  show "$runtime_dir/build.log"
  fail_all 'mvn packageが失敗しました（Servletのコードをcompileできません）'
fi
if [ ! -s target/cart.war ] \
    || ! jar tf target/cart.war | grep -Fq 'WEB-INF/classes/cafe/web/CartServlet.class'; then
  show "$runtime_dir/build.log"
  fail_all 'CartServletを含むcart.warがありません'
fi

if ! mvn -q liberty:create liberty:install-feature >"$runtime_dir/liberty-install.log" 2>&1; then
  show "$runtime_dir/liberty-install.log"
  fail_all 'サーバーまたはFeatureを準備できません（初回は取得に時間がかかります）'
fi
mkdir -p target/liberty/wlp/usr/servers/defaultServer/apps
cp target/cart.war target/liberty/wlp/usr/servers/defaultServer/apps/cart.war

if ! javac -encoding UTF-8 --release 21 -d "$runtime_dir/classes" RuntimeProbe.java \
    >"$runtime_dir/probe-compile.log" 2>&1; then
  show "$runtime_dir/probe-compile.log"
  fail_all 'HTTP検査プログラムをコンパイルできません'
fi

"$server_cmd" run defaultServer >"$runtime_dir/server-console.log" 2>&1 &
server_pid=$!

ready=0
attempt=0
while [ "$attempt" -lt 40 ]; do
  if java -cp "$runtime_dir/classes" RuntimeProbe wait "$base_url" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  attempt=$((attempt + 1))
  sleep 1
done

messages="target/liberty/wlp/usr/servers/defaultServer/logs/messages.log"
if [ "$ready" -ne 1 ]; then
  tail -40 "$runtime_dir/server-console.log" 2>/dev/null | sed 's/^JQ_CHECK/JQ-CHECK/' || true
  tail -40 "$messages" 2>/dev/null | sed 's/^JQ_CHECK/JQ-CHECK/' || true
  fail_all '動的ポートでサーバーとWARを起動できません'
fi

java -cp "$runtime_dir/classes" RuntimeProbe verify "$base_url"
probe_status=$?

stop_server
server_pid=""

grep -E 'CWWKF0012I|CWWKZ0001I|SRVE0777E|Error 500' "$messages" 2>/dev/null \
  | tail -4 | sed 's/^JQ_CHECK/JQ-CHECK/'

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' '利用者ごとの状態をServletのフィールドに置いていないか（全員で共有される）'
printf '%s\n' 'ログインの前後でセッションIDが変わっているか'
printf '%s\n' 'ログアウトでセッションを無効にしているか（値を消すだけでは残る）'
printf '%s\n' 'CookieにHttpOnlyとSameSiteを付けているか'

exit "$probe_status"
