#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

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
  for id in tasks-parallel tasks-timeout tasks-error tasks-shutdown; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

value() {
  sed -n "s/^RESULT	$1	//p" out/harness.log | head -1
}

if ! javac -encoding UTF-8 --release 21 -d out/classes \
    TaskHarness.java ExitProbe.java exercise/ReportService.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'ReportService.javaをcompileできません'
fi

if ! java -cp out/classes TaskHarness >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all '測定を完了できませんでした'
fi

printf '%s\n' '--- 実測 ---'
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

# ── 1. 並行に走ったか（4本のプールへ8件。逐次なら同時に走るのは1件だけ）──────────
peak="$(value parallel-peak)"
parallel_millis="$(value parallel-millis)"
order="$(value parallel-order)"
expected_order='r0,r1,r2,r3,r4,r5,r6,r7'
if [ -z "$peak" ]; then
  bad tasks-parallel "並行度を測れませんでした（$(value parallel-error)）"
elif [ "$order" != "$expected_order" ]; then
  bad tasks-parallel "結果が投入順ではありません（$order）。投入順のまま返してください"
elif [ "$peak" -ge 4 ] && [ "$parallel_millis" -lt 800 ]; then
  pass tasks-parallel "8件が同時${peak}件まで並行に走り、${parallel_millis}msで終わりました（逐次なら1200ms超）"
else
  bad tasks-parallel "同時に走ったのは最大${peak}件、所要${parallel_millis}msでした。4本のプールへ投入して並行に走らせてください"
fi

# ── 2. 遅いjobを打ち切ったか ────────────────────────────────────────────
timeout_millis="$(value timeout-millis)"
timeout_order="$(value timeout-order)"
expected_timeout='fast-1,TIMEOUT,fast-2,TIMEOUT'
if [ -z "$timeout_millis" ]; then
  bad tasks-timeout "打ち切りを測れませんでした（$(value timeout-error)）"
elif [ "$timeout_order" = "$expected_timeout" ] && [ "$timeout_millis" -lt 2500 ]; then
  pass tasks-timeout "600msの制限で遅い2件を打ち切り、${timeout_millis}msで戻りました"
else
  bad tasks-timeout "結果は${timeout_order}、所要${timeout_millis}msでした。期待は${expected_timeout}で、4秒かかるjobを待たずに戻ることです"
fi

# ── 3. 失敗の原因が呼び出し元へ届いたか ──────────────────────────────────
thrown="$(value error-thrown)"
message="$(value error-message)"
if [ "$thrown" = none ]; then
  bad tasks-error "jobが例外を投げたのに、collectは例外を投げず $(value error-results) を返しました"
elif printf '%s' "$message" | grep -q 'boom-42'; then
  pass tasks-error "失敗したjobの原因が呼び出し元へ届きました（${message}）"
else
  bad tasks-error "例外は届きましたが原因が失われています（${message}）。原因をcauseに保つか、原因そのものを投げ直してください"
fi

# ── 4. 閉じたあとJVMが自力で終了できるか（プールのスレッドが残っていないか）────────
java -cp out/classes ExitProbe >out/exit.log 2>&1 &
probe_pid=$!
waited=0
while [ "$waited" -lt 100 ]; do
  kill -0 "$probe_pid" 2>/dev/null || break
  waited=$((waited + 1))
  sleep 0.1
done

if kill -0 "$probe_pid" 2>/dev/null; then
  kill -9 "$probe_pid" 2>/dev/null || true
  wait "$probe_pid" 2>/dev/null || true
  show '別JVMの出力' out/exit.log
  bad tasks-shutdown '閉じたあとも10秒たってJVMが終了しませんでした。プールのスレッドが残っています（close()で終わらせてください）'
else
  wait "$probe_pid" 2>/dev/null || true
  if grep -q 'EXIT-READY' out/exit.log 2>/dev/null; then
    pass tasks-shutdown 'close()のあと、別JVMが自力で終了しました（残ったスレッドがありません）'
  else
    show '別JVMの出力' out/exit.log
    bad tasks-shutdown 'ReportServiceを使う別JVMが途中で落ちました'
  fi
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'invokeAll は投入順のFutureを返す。制限時間つきの版は間に合わなかったタスクを取り消す'
printf '%s\n' '取り消されたFutureのget と、失敗したjobのget では投げられる例外が違う'
printf '%s\n' 'プールのスレッドはdaemonではない。shutdown を呼ばないとJVMは終われない'
exit "$fail"
