#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
mkdir -p runtime/classes

cleanup() {
  if [ -n "${server_pid:-}" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if ! javac --release 21 --add-modules jdk.httpserver -d runtime/classes \
    ApiServer.java exercise/ApiClient.java >runtime/compile.log 2>&1; then
  cat runtime/compile.log
  printf 'JQ_CHECK\tFAIL\thttp-success\tHTTP clientをコンパイルできません\n'
  printf 'JQ_CHECK\tFAIL\thttp-not-found\tHTTP clientをコンパイルできません\n'
  printf 'JQ_CHECK\tFAIL\thttp-timeout\tHTTP clientをコンパイルできません\n'
  exit 1
fi

java --add-modules jdk.httpserver -cp runtime/classes ApiServer "$port" >runtime/server.log 2>&1 &
server_pid=$!
sleep 1
java -cp runtime/classes ApiClient "http://localhost:$port" >runtime/client.log 2>&1
client_status=$?
cat runtime/server.log runtime/client.log

fail=0
if [ "$client_status" -eq 0 ] && grep -Fq '200 {"id":1,"name":"Java"}' runtime/client.log; then
  printf 'JQ_CHECK\tPASS\thttp-success\t実サーバーからHTTP 200とJSONを受信しました\n'
else
  printf 'JQ_CHECK\tFAIL\thttp-success\tHTTP 200と期待したJSONを受信できません\n'; fail=1
fi
if grep -Fq '404 {"error":"not found"}' runtime/client.log; then
  printf 'JQ_CHECK\tPASS\thttp-not-found\tHTTP 404のJSONエラーを区別できました\n'
else
  printf 'JQ_CHECK\tFAIL\thttp-not-found\tHTTP 404の応答を正しく扱えていません\n'; fail=1
fi
if grep -Fq 'TIMEOUT /api/slow' runtime/client.log; then
  printf 'JQ_CHECK\tPASS\thttp-timeout\t遅い応答をrequest timeoutとして処理しました\n'
else
  printf 'JQ_CHECK\tFAIL\thttp-timeout\t2秒遅延より短いrequest timeoutを指定してください\n'; fail=1
fi
exit "$fail"
