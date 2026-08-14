#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

# testが落ちても止めずに依存一覧まで進める。落ちた理由と選ばれた版を同時に見せるため。
mvn -B clean test dependency:list -Dmaven.test.failure.ignore=true >out/maven.log 2>&1

# 1. 実行時に落ちず、testが通る
sum_attr() {
  grep -ho "$1=\"[0-9]*\"" target/surefire-reports/TEST-*.xml 2>/dev/null \
    | sed 's/[^0-9]//g' | awk '{ sum += $1 } END { print sum + 0 }'
}
tests="$(sum_attr tests)"
failures="$(sum_attr failures)"
errors="$(sum_attr errors)"
if [ "${tests}" -ge 2 ] && [ "${failures}" -eq 0 ] && [ "${errors}" -eq 0 ]; then
  pass deps-runtime-ok "2件のtestが実行時エラーなしで通りました"
else
  bad deps-runtime-ok "testが通りません（実行 ${tests} 件・失敗 ${failures} 件・エラー ${errors} 件）。下のMavenの出力で落ちた理由を読んでください"
fi

# 2. 解決後のjackson系の版がそろっている
resolved() {
  sed -n "s/.*com\.fasterxml\.jackson\.core:$1:jar:\([0-9][^:]*\):.*/\1/p" out/maven.log | head -1
}
core="$(resolved jackson-core)"
databind="$(resolved jackson-databind)"
annotations="$(resolved jackson-annotations)"
if [ -n "$core" ] && [ "$core" = "$databind" ] && [ "$core" = "$annotations" ]; then
  pass deps-aligned "jackson-core・databind・annotationsが同じ ${core} に解決されました"
else
  bad deps-aligned "解決後の版がそろっていません（core=${core:-不明} databind=${databind:-不明} annotations=${annotations:-不明}）"
fi

# 3. 古い方へ落として合わせていない
major="$(printf '%s' "${core:-0.0}" | cut -d. -f1)"
minor="$(printf '%s' "${core:-0.0}" | cut -d. -f2)"
case "$major$minor" in
  *[!0-9]*|'') major=0; minor=0 ;;
esac
if [ "$major" -gt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -ge 18 ]; }; then
  pass deps-not-downgraded "databindが前提とする2.18.2以降へそろえました"
else
  bad deps-not-downgraded "古い版へ落として合わせないでください（現在 ${core:-不明}）。2.18.2以降へそろえます"
fi

grep -E 'NoClassDefFoundError|NoSuchMethodError|Tests run:|BUILD|com\.fasterxml\.jackson' out/maven.log \
  2>/dev/null | head -20 | sed 's/^JQ_CHECK/JQ-CHECK/'
exit "$fail"
