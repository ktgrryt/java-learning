#!/usr/bin/env sh
set -u

mkdir -p runtime/classes
duration="$(sed -n 's/^duration=//p' exercise/jfr.options)"
settings="$(sed -n 's/^settings=//p' exercise/jfr.options)"
case "$duration" in 2s|3s|4s|5s) ;; *) duration=1s ;; esac
case "$settings" in default|profile) ;; *) settings=invalid ;; esac

cleanup() {
  if [ -n "${app_pid:-}" ]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if [ "$settings" = invalid ] || ! javac --release 21 -d runtime/classes AllocationDemo.java; then
  printf 'JQ_CHECK\tFAIL\tjfr-recording\tJFRの設定またはJavaソースが不正です\n'
  printf 'JQ_CHECK\tFAIL\tjfr-summary\tJFR記録を要約できません\n'
  printf 'JQ_CHECK\tFAIL\tjfr-profile\tprofile設定を選んでください\n'
  exit 1
fi

java -XX:StartFlightRecording="name=lab,settings=$settings,duration=$duration,filename=runtime/lab.jfr" \
  -cp runtime/classes AllocationDemo >runtime/application.log 2>&1 &
app_pid=$!
seconds="${duration%s}"
sleep "$((seconds + 2))"

fail=0
if [ -s runtime/lab.jfr ]; then
  printf 'JQ_CHECK\tPASS\tjfr-recording\t実行中JVMからJFRファイルを記録しました\n'
else
  printf 'JQ_CHECK\tFAIL\tjfr-recording\tJFRファイルが作成されませんでした\n'; fail=1
fi
if jfr summary runtime/lab.jfr >runtime/summary.txt 2>&1 && grep -Fq 'jdk.ThreadSleep' runtime/summary.txt; then
  printf 'JQ_CHECK\tPASS\tjfr-summary\tjfr summaryで記録イベントを読み取りました\n'
else
  printf 'JQ_CHECK\tFAIL\tjfr-summary\t記録からイベント一覧を読み取れません\n'; fail=1
fi
if [ "$settings" = profile ] && [ "$seconds" -ge 2 ]; then
  printf 'JQ_CHECK\tPASS\tjfr-profile\t短い診断向けにprofile設定と十分な記録時間を選びました\n'
else
  printf 'JQ_CHECK\tFAIL\tjfr-profile\tsettings=profile、durationは2〜5秒にしてください\n'; fail=1
fi
cat runtime/application.log runtime/summary.txt 2>/dev/null || true
exit "$fail"
