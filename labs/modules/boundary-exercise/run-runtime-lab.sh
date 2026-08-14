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

# 1. 2つのmoduleをまとめてcompileする
compiled=0
if javac -d out/mods --module-source-path src -m cafe.greeting,cafe.app \
    >out/javac.log 2>&1; then
  compiled=1
  pass modules-compile '2つのmoduleをmodule-source-pathでまとめてcompileしました'
else
  bad modules-compile 'compileできません。使うmoduleをrequiresで宣言し、使われるpackageをexportsで公開してください'
fi

# 2. 公開したpackageだけが他moduleから見える
if [ "$compiled" -eq 1 ]; then
  javac -d out/probe-public --module-path out/mods \
      --module-source-path probe-public -m cafe.probe >out/probe-public.log 2>&1
  public_ok=$?
  javac -d out/probe-internal --module-path out/mods \
      --module-source-path probe-internal -m cafe.probe >out/probe-internal.log 2>&1
  internal_ok=$?
  if [ "$public_ok" -eq 0 ] && [ "$internal_ok" -ne 0 ]; then
    pass modules-boundary '公開したpackageは他moduleから使え、内部packageは使えないことを確認しました'
  elif [ "$public_ok" -ne 0 ]; then
    bad modules-boundary '利用側が使うpackageを公開してください（他moduleからcompileできません）'
  else
    bad modules-boundary '内部実装のpackageまで公開しています。公開するのは利用側が使うpackageだけです'
  fi
else
  bad modules-boundary '先にcompileを通してください'
fi

# 3. JARへまとめ、module-pathから起動する
expected='Hello, Java!'
run_output=''
if [ "$compiled" -eq 1 ] \
    && jar --create --file out/cafe.greeting.jar -C out/mods/cafe.greeting . \
        >>out/jar.log 2>&1 \
    && jar --create --file out/cafe.app.jar --main-class cafe.app.Main \
        -C out/mods/cafe.app . >>out/jar.log 2>&1; then
  run_output="$(java --module-path "out/cafe.greeting.jar:out/cafe.app.jar" -m cafe.app 2>&1)"
fi
if [ "$run_output" = "$expected" ]; then
  pass modules-jar-run 'JARへまとめ、module-pathから起動して期待した出力を得ました'
else
  bad modules-jar-run "module-pathからの起動で ${expected} を出力してください（実際: ${run_output:-出力なし}）"
fi

# 4. jar --describe-module が公開範囲を示す
describe=''
if [ -f out/cafe.greeting.jar ]; then
  describe="$(jar --describe-module --file out/cafe.greeting.jar 2>&1)"
fi
if printf '%s' "$describe" | grep -q 'exports cafe.greeting$' \
    && ! printf '%s' "$describe" | grep -q 'exports cafe.greeting.internal'; then
  pass modules-describe 'jar --describe-moduleが公開packageだけをexportsとして示しました'
else
  bad modules-describe 'jar --describe-moduleのexportsが、利用側が使うpackageだけになるようにしてください'
fi

show 'javac（module）' out/javac.log
show 'javac（内部packageを使うprobe）' out/probe-internal.log
printf '%s\n' "--- jar --describe-module ---"
printf '%s\n' "$describe" | sed 's/^JQ_CHECK/JQ-CHECK/'
exit "$fail"
