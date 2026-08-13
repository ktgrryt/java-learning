#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
base_url="http://127.0.0.1:$port"
mkdir -p runtime/classes
server_pid=""

stop_server() {
  if [ -z "$server_pid" ]; then return; fi
  kill "$server_pid" 2>/dev/null || true
  attempt=0
  while [ "$attempt" -lt 10 ] && kill -0 "$server_pid" 2>/dev/null; do
    attempt=$((attempt + 1))
    sleep 1
  done
  if kill -0 "$server_pid" 2>/dev/null; then kill -9 "$server_pid" 2>/dev/null || true; fi
  wait "$server_pid" 2>/dev/null || true
}
trap stop_server EXIT INT TERM

fail_remaining() {
  message="$1"
  printf 'JQ_CHECK\tFAIL\tquarkus-jvm-package\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-api\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-validation\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-health\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-stop\t%s\n' "$message"
}

if ! mvn -q package >runtime/package.log 2>&1; then
  cat runtime/package.log
  printf 'JQ_CHECK\tFAIL\tquarkus-tests\tmvn package内のJUnitが失敗しました\n'
  fail_remaining 'testまたはpackage失敗のためJVMアプリを起動していません'
  exit 1
fi
printf 'JQ_CHECK\tPASS\tquarkus-tests\tmvn package内のJUnitが成功しました\n'

if [ ! -s target/quarkus-app/quarkus-run.jar ] || [ ! -d target/quarkus-app/lib ]; then
  fail_remaining 'target/quarkus-app一式を作成できません'
  exit 1
fi
printf 'JQ_CHECK\tPASS\tquarkus-jvm-package\tquarkus-run.jarと依存libを含むJVM配布物を作成しました\n'

if ! javac --release 21 -d runtime/classes RuntimeProbe.java >runtime/probe-compile.log 2>&1; then
  cat runtime/probe-compile.log
  printf 'JQ_CHECK\tFAIL\tquarkus-api\tHTTP probeをコンパイルできません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-validation\tHTTP probeをコンパイルできません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-health\tHTTP probeをコンパイルできません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-stop\tQuarkusを起動していません\n'
  exit 1
fi

java -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port="$port" \
  -jar target/quarkus-app/quarkus-run.jar >runtime/server.log 2>&1 &
server_pid=$!

ready=0
attempt=0
while [ "$attempt" -lt 30 ]; do
  if java -cp runtime/classes RuntimeProbe wait "$base_url" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  attempt=$((attempt + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  tail -100 runtime/server.log 2>/dev/null || true
  printf 'JQ_CHECK\tFAIL\tquarkus-api\t動的ポートでJVMモードを起動できません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-validation\tJVMモードを起動できません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-health\tJVMモードを起動できません\n'
  printf 'JQ_CHECK\tFAIL\tquarkus-stop\t起動に失敗したプロセスをcleanupします\n'
  exit 1
fi

java -cp runtime/classes RuntimeProbe verify "$base_url"
probe_status=$?
stop_server
server_pid=""
if java -cp runtime/classes RuntimeProbe stopped "$base_url" >/dev/null 2>&1; then
  printf 'JQ_CHECK\tPASS\tquarkus-stop\t検証後にQuarkus JVM processとHTTP listenerを停止しました\n'
  stop_status=0
else
  printf 'JQ_CHECK\tFAIL\tquarkus-stop\t検証後もQuarkusのHTTP listenerが応答しています\n'
  stop_status=1
fi
if [ "$probe_status" -ne 0 ] || [ "$stop_status" -ne 0 ]; then exit 1; fi
