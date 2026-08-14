#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

# Mavenの出力には学習者が書いた値が混ざる。行頭の印だけ無効化して読みやすさは保つ。
show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

# 1回の実行でtest・package・依存一覧まで進める。
# packageはtest phaseを含むので、testを飛ばす設定が残っていればここで分かる。
mvn -B clean package dependency:list >out/maven.log 2>&1

# 1. testが実際に走り、全て成功する
sum_attr() {
  grep -ho "$1=\"[0-9]*\"" target/surefire-reports/TEST-*.xml 2>/dev/null \
    | sed 's/[^0-9]//g' | awk '{ sum += $1 } END { print sum + 0 }'
}
tests="$(sum_attr tests)"
failures="$(sum_attr failures)"
errors="$(sum_attr errors)"
# 変数の直後に日本語が続くと、shellが変数名へバイトを巻き込む。必ず${}で閉じる。
if [ "${tests}" -eq 9 ] && [ "${failures}" -eq 0 ] && [ "${errors}" -eq 0 ]; then
  pass maven-tests-run "9件のtestが実際に走り、すべて成功しました"
else
  bad maven-tests-run "9件のtestが走って全て成功する状態にしてください（実行 ${tests} 件・失敗 ${failures} 件・エラー ${errors} 件）"
fi

# 2. JUnitはtestのときだけ必要な依存として解決される
junit_test="$(grep -c 'org\.junit\.jupiter:[^ ]*:test' out/maven.log 2>/dev/null || true)"
junit_other="$(grep -c 'org\.junit\.jupiter:[^ ]*:compile' out/maven.log 2>/dev/null || true)"
if [ "${junit_test:-0}" -gt 0 ] && [ "${junit_other:-0}" -eq 0 ]; then
  pass maven-test-scope "JUnitがtest scopeの依存として解決されました"
else
  bad maven-test-scope "JUnitはtestのときだけ必要です。scopeを宣言し、実行時の依存に混ぜないでください"
fi

# 3. 配布物には本体のclassだけが入る
jar='target/testing-maven-lab-1.0.0-SNAPSHOT.jar'
[ -f "$jar" ] && jar tf "$jar" >out/jar.txt 2>&1
if [ -s out/jar.txt ] \
    && grep -qx 'cafe/lab/PriceService.class' out/jar.txt \
    && ! grep -q 'Test\.class$' out/jar.txt; then
  pass maven-jar-content "JARに本体のclassだけが入り、testのclassは入っていません"
else
  bad maven-jar-content "target/classesだけをJARへ入れてください（testのclassやbuildの中間物は配布しません）"
fi

# 4. 同じ手順を将来も再現できるよう、pluginの版を明示する
if [ -s out/maven.log ] && ! grep -q "build.plugins.plugin.version" out/maven.log; then
  pass maven-pinned-versions "依存とpluginの版が明示され、版未指定の警告は出ませんでした"
else
  bad maven-pinned-versions "版を明示していないpluginがあります。Mavenの警告を読んで版を固定してください"
fi

grep -E 'BUILD|ERROR|WARNING|Tests run:|org\.junit\.jupiter:' out/maven.log 2>/dev/null \
  | head -25 | sed 's/^JQ_CHECK/JQ-CHECK/'
show 'JARの中身' out/jar.txt
exit "$fail"
