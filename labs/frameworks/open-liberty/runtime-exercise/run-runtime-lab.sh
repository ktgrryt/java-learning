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

cleanup() {
  stop_server
}
trap cleanup EXIT INT TERM

fail_after_war() {
  message="$1"
  printf 'JQ_CHECK\tFAIL\tliberty-features\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tliberty-rest\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tliberty-validation\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tliberty-health\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tliberty-stop\t%s\n' "$message"
}

if ! mvn -q test package >"$runtime_dir/build.log" 2>&1; then
  cat "$runtime_dir/build.log"
  printf 'JQ_CHECK\tFAIL\tliberty-war\tmvn test packageが失敗しました\n'
  fail_after_war 'WARを作成できないためLibertyを起動していません'
  exit 1
fi
if [ ! -s target/greeting.war ] \
    || ! jar tf target/greeting.war | grep -Fq 'WEB-INF/classes/example/greeting/GreetingResource.class'; then
  cat "$runtime_dir/build.log"
  printf 'JQ_CHECK\tFAIL\tliberty-war\t実行対象クラスを含むgreeting.warがありません\n'
  fail_after_war '配備可能なWARがないためLibertyを起動していません'
  exit 1
fi
printf 'JQ_CHECK\tPASS\tliberty-war\tmvn test後にResourceを含むgreeting.warを作成しました\n'

if ! mvn -q liberty:create liberty:install-feature >"$runtime_dir/liberty-install.log" 2>&1; then
  cat "$runtime_dir/liberty-install.log"
  fail_after_war 'Open LibertyランタイムまたはFeatureを準備できません'
  exit 1
fi
mkdir -p target/liberty/wlp/usr/servers/defaultServer/apps
cp target/greeting.war target/liberty/wlp/usr/servers/defaultServer/apps/greeting.war

if ! javac --release 21 -d "$runtime_dir/classes" RuntimeProbe.java \
    >"$runtime_dir/probe-compile.log" 2>&1; then
  cat "$runtime_dir/probe-compile.log"
  fail_after_war 'HTTP検査プログラムをコンパイルできません'
  exit 1
fi

"$server_cmd" run defaultServer >"$runtime_dir/server-console.log" 2>&1 &
server_pid=$!

ready=0
attempt=0
while [ "$attempt" -lt 30 ]; do
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
  tail -100 "$runtime_dir/server-console.log" 2>/dev/null || true
  tail -100 "$messages" 2>/dev/null || true
  fail_after_war '動的ポートでOpen LibertyとWARを起動できません'
  exit 1
fi

feature_line=$(grep -F 'CWWKF0012I' "$messages" | tail -1 || true)
features_ok=1
for feature in restfulWS-4.0 cdi-4.1 validation-3.1 jsonb-3.0 mpHealth-4.0 mpConfig-3.1; do
  if ! printf '%s' "$feature_line" | grep -Fq "$feature"; then features_ok=0; fi
done
if [ "$features_ok" -eq 1 ]; then
  printf 'JQ_CHECK\tPASS\tliberty-features\tCWWKF0012I起動ログで必要な6 Featureを確認しました\n'
else
  printf 'JQ_CHECK\tFAIL\tliberty-features\tCWWKF0012I起動ログに必要なFeatureがそろっていません\n'
fi

java -cp "$runtime_dir/classes" RuntimeProbe verify "$base_url"
probe_status=$?

stop_server
server_pid=""
if java -cp "$runtime_dir/classes" RuntimeProbe stopped "$base_url" >/dev/null 2>&1; then
  printf 'JQ_CHECK\tPASS\tliberty-stop\t検証後にOpen LibertyプロセスとHTTP listenerを停止しました\n'
  stop_status=0
else
  printf 'JQ_CHECK\tFAIL\tliberty-stop\t検証後もOpen LibertyのHTTP listenerが応答しています\n'
  stop_status=1
fi

if [ "$features_ok" -ne 1 ] || [ "$probe_status" -ne 0 ] || [ "$stop_status" -ne 0 ]; then
  exit 1
fi
