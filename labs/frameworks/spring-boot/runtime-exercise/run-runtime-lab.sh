#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
base_url="http://localhost:$port"
mkdir -p runtime/classes
server_pid=""
stopped=0

cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

fail_remaining() {
  message="$1"
  printf 'JQ_CHECK\tFAIL\tspring-api\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tspring-validation\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tspring-health\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tspring-stop\t%s\n' "$message"
}

if ! mvn -q test >runtime/test.log 2>&1; then
  cat runtime/test.log
  printf 'JQ_CHECK\tFAIL\tspring-tests\tmvn testが失敗しました\n'
  fail_remaining 'テスト失敗のためサーバーを起動していません'
  exit 1
fi
cat runtime/test.log
printf 'JQ_CHECK\tPASS\tspring-tests\tmvn testが成功しました\n'

if ! mvn -q -DskipTests package >runtime/package.log 2>&1; then
  cat runtime/package.log
  fail_remaining '実行可能JARを作成できません'
  exit 1
fi
if ! javac --release 21 -d runtime/classes RuntimeProbe.java >runtime/probe-compile.log 2>&1; then
  cat runtime/probe-compile.log
  fail_remaining 'HTTP検査プログラムをコンパイルできません'
  exit 1
fi

java -jar target/greeting-spring-runtime-exercise-1.0.0.jar \
  "--server.address=127.0.0.1" "--server.port=$port" >runtime/server.log 2>&1 &
server_pid=$!

ready=0
attempt=0
while [ "$attempt" -lt 40 ]; do
  if java -cp runtime/classes RuntimeProbe wait "$base_url" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  attempt=$((attempt + 1))
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  cat runtime/server.log
  fail_remaining '動的ポートでSpring Bootを起動できません'
  exit 1
fi

java -cp runtime/classes RuntimeProbe verify "$base_url"
probe_status=$?

kill "$server_pid" 2>/dev/null || true
wait "$server_pid" 2>/dev/null || true
if kill -0 "$server_pid" 2>/dev/null; then
  printf 'JQ_CHECK\tFAIL\tspring-stop\t検証後もSpring Bootプロセスが動いています\n'
  stopped=1
else
  printf 'JQ_CHECK\tPASS\tspring-stop\t検証後にSpring Bootプロセスを停止しました\n'
fi
server_pid=""

if [ "$probe_status" -ne 0 ] || [ "$stopped" -ne 0 ]; then exit 1; fi
