#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
base_url="http://localhost:$port"

rm -rf out
mkdir -p out
server_pid=""

cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

# 学習者のファイル内容がそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

fail_all() {
  for id in retry-transient retry-permanent retry-deadline observability-log; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

value() {
  sed -n "s/^RESULT	$1	//p" out/harness.log | head -1
}

if ! javac -encoding UTF-8 --release 21 --add-modules jdk.httpserver -d out/classes \
    FlakyServer.java RetryHarness.java exercise/OrderClient.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'OrderClient.javaをcompileできません'
fi

java --add-modules jdk.httpserver -cp out/classes FlakyServer "$port" >out/server.log 2>&1 &
server_pid=$!
waited=0
while [ "$waited" -lt 100 ]; do
  grep -q 'SERVER-READY' out/server.log 2>/dev/null && break
  kill -0 "$server_pid" 2>/dev/null || break
  waited=$((waited + 1))
  sleep 0.1
done
if ! grep -q 'SERVER-READY' out/server.log 2>/dev/null; then
  show 'serverの出力' out/server.log
  fail_all '検査用serverを起動できません'
fi

if ! java -cp out/classes RetryHarness "$base_url" >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all 'clientの実行が完了しませんでした'
fi

printf '%s\n' '--- 実測（serverが数えた受信回数つき）---'
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

server_counts="$(value server-counts)"
transient_seen="$(printf '%s' "$server_counts" | sed -n 's/.*transient=\([0-9]*\).*/\1/p')"
permanent_seen="$(printf '%s' "$server_counts" | sed -n 's/.*permanent=\([0-9]*\).*/\1/p')"
if [ -z "$transient_seen" ] || [ -z "$permanent_seen" ]; then
  show '実行の出力' out/harness.log
  fail_all 'serverの受信回数を読めませんでした'
fi

# ── 1. 一時的な失敗は再試行して成功させる（503が2回 → 3回目で成功）──────────
transient_outcome="$(value transient-outcome)"
if [ "$transient_outcome" = ok ] && [ "$transient_seen" = 3 ]; then
  pass retry-transient "503を2回受けたあと3回目で成功しました（serverが数えた受信回数=3）"
elif [ "$transient_outcome" = ok ]; then
  bad retry-transient "成功しましたが、serverが受け取ったのは${transient_seen}回でした。上限4回までの再試行で、送りすぎ・送り足らずを直してください"
else
  bad retry-transient "取得できませんでした（${transient_outcome}）。serverの受信回数は${transient_seen}回です。5xxは一時的な失敗として再試行してください"
fi

# ── 2. 恒久的な失敗は再試行しない（400は何度送っても直らない）────────────────
permanent_outcome="$(value permanent-outcome)"
if [ "$permanent_outcome" = threw ] && [ "$permanent_seen" = 1 ]; then
  pass retry-permanent "400を受けて1回でやめました（serverが数えた受信回数=1）"
elif [ "$permanent_outcome" = threw ]; then
  bad retry-permanent "やめましたが、serverは${permanent_seen}回受け取りました。4xxは再試行せず、その場でやめてください"
else
  bad retry-permanent "400なのに失敗として扱っていません（${permanent_outcome}）。ステータスを見て、恒久的な失敗はその場でやめてください"
fi

# ── 3. 遅い応答を打ち切り、締め切りを超えない ────────────────────────────
slow_outcome="$(value slow-outcome)"
slow_millis="$(value slow-millis)"
if [ "$slow_outcome" = threw ] && [ "$slow_millis" -lt 2800 ]; then
  pass retry-deadline "4秒かかる相手を${slow_millis}msで打ち切りました（1回500ms・締め切り1500ms）"
elif [ "$slow_outcome" = threw ]; then
  bad retry-deadline "打ち切りましたが${slow_millis}msかかりました。1回ごとの制限時間と全体の締め切りを両方効かせてください"
else
  bad retry-deadline "4秒待って応答を受けています（${slow_millis}ms）。1回の要求に制限時間を付けてください"
fi

# ── 4. 相関IDと試行回数が1行のログに残る ────────────────────────────────
log_count="$(value log-count)"
transient_log="$(grep '^RESULT	log-' out/harness.log | grep 'cid=cid-transient' | head -1 | sed 's/^RESULT	log-[0-9]*	//')"
permanent_log="$(grep '^RESULT	log-' out/harness.log | grep 'cid=cid-permanent' | head -1 | sed 's/^RESULT	log-[0-9]*	//')"
slow_log="$(grep '^RESULT	log-' out/harness.log | grep 'cid=cid-slow' | head -1 | sed 's/^RESULT	log-[0-9]*	//')"

log_ok=1
for line in "transient:$transient_log" "permanent:$permanent_log" "slow:$slow_log"; do
  body="$(printf '%s' "$line" | cut -d: -f2-)"
  [ -n "$body" ] || log_ok=0
done
if [ "$log_count" != 3 ]; then
  bad observability-log "ログが${log_count}行です。1件の取得につき1行、3件ぶんを残してください"
elif [ "$log_ok" -ne 1 ]; then
  bad observability-log '相関ID（cid-transient / cid-permanent / cid-slow）を含む行が足りません'
elif ! printf '%s' "$transient_log" | grep -q 'outcome=ok attempts=3 '; then
  bad observability-log "一時的な失敗の行が実測と合いません（${transient_log}）。serverは3回受け取っています"
elif ! printf '%s' "$permanent_log" | grep -q 'outcome=failed attempts=1 '; then
  bad observability-log "恒久的な失敗の行が実測と合いません（${permanent_log}）。serverは1回受け取っています"
elif ! printf '%s' "$slow_log" | grep -q 'outcome=failed attempts=[2-4] '; then
  bad observability-log "遅い相手の行が実測と合いません（${slow_log}）。打ち切って再試行した回数を書いてください"
else
  pass observability-log '3件すべて、相関ID・結果・試行回数が実測と一致する1行で残りました'
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' '5xx・応答なし と 4xx を同じ扱いにしていないか'
printf '%s\n' '1回ごとの制限時間（HttpRequest.timeout）と、全体の締め切りを別に持っているか'
printf '%s\n' 'attempts は「実際に送った回数」。serverが数えた回数と合うか'
exit "$fail"
