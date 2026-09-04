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
  for id in jshell-runs jshell-values jpackage-image jpackage-runs jpackage-version; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

# ── 配布物をcompileしてJARにまとめる（ここは採点側が用意する）──────────────
if ! javac -encoding UTF-8 -d out/classes app/Pricing.java app/Main.java \
    >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all '参照用コードをcompileできません。JDKの状態を確認してください'
fi
mkdir -p out/input
if ! jar --create --file out/input/app.jar --main-class cafe.pricing.Main \
    -C out/classes . >out/jar.log 2>&1; then
  show 'jarの失敗' out/jar.log
  fail_all 'JARを作れません'
fi

# ── 1. jshellでクラスの振る舞いを確かめる ──────────────────────────────
# --execution local … 別プロセスを起こさずに評価する（起動が速く、環境差も小さい）
# -J-cp … local実行では評価側もJShell本体のclass loaderを使うため、そこにもappを渡す
# --class-path … snippetをcompileするときにもappを見えるようにする
# -q … 起動時の案内を出さない。出力の比較を邪魔しない
#
# 値を変えて2回動かす。定数を直接書いた解答は片方で外れる。
runs_ok=1
values_ok=1
round_no=0
for triple in '480 3 333' '1250 4 1999'; do
  round_no=$((round_no + 1))
  set -- $triple
  price=$1
  count=$2
  odd=$3
  # 期待値は採点側でも同じ規則で計算する（税込み = 単価 + 単価 * 10 / 100、端数は切り捨て）
  tax=$((price + price * 10 / 100))
  total=$((tax * count))
  round=$((odd + odd * 10 / 100))

  if JQ_PRICE="$price" JQ_COUNT="$count" JQ_ODD="$odd" \
      jshell -q --execution local -J-cp -Jout/classes \
      --class-path out/classes exercise/probe.jsh \
      >"out/jshell-${round_no}.log" 2>&1; then
    :
  else
    runs_ok=0
    show "jshellの出力（${round_no}回目）" "out/jshell-${round_no}.log"
  fi
  grep -E '^(tax|total|round)=' "out/jshell-${round_no}.log" \
      >"out/jshell-values-${round_no}.txt" 2>/dev/null
  printf 'tax=%s\ntotal=%s\nround=%s\n' "$tax" "$total" "$round" \
      >"out/jshell-expected-${round_no}.txt"
  if ! cmp -s "out/jshell-values-${round_no}.txt" "out/jshell-expected-${round_no}.txt"; then
    values_ok=0
    show "jshellが出した行（${round_no}回目）" "out/jshell-values-${round_no}.txt"
  fi
done

if [ "$runs_ok" -eq 1 ]; then
  pass jshell-runs 'jshellがスクリプトを最後まで評価しました（2回）'
else
  bad jshell-runs 'jshellが異常終了しました。最後に /exit を書いているか、import が正しいか確認してください'
fi

if [ "$values_ok" -eq 1 ]; then
  pass jshell-values '違う値で2回動かしても、税込み・合計・端数が正しく出ました'
else
  bad jshell-values 'tax= / total= / round= の3行を、この順で出してください。値は環境変数から読んでPricingで計算します（定数を直接書くと2回目で外れます）'
fi

# ── 2. jpackageで配布物を作る ────────────────────────────────────────
# 学習者の指定を読む（コメントと空行は落とす）
opts=""
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|\#*) continue ;;
  esac
  opts="$opts $line"
done < exercise/package.options

# shellcheck disable=SC2086
if jpackage --dest out/dist --input out/input $opts >out/jpackage.log 2>&1; then
  if [ -d out/dist ] && [ -n "$(find out/dist -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    pass jpackage-image 'jpackageが配布物を生成しました'
  else
    show 'jpackageの出力' out/jpackage.log
    bad jpackage-image 'jpackageは成功しましたが、生成物が見つかりません'
  fi
else
  show 'jpackageの出力' out/jpackage.log
  bad jpackage-image 'jpackageが失敗しました。指定が4つそろっているか確認してください'
fi

# 生成物の中の起動可能ファイルを探す。置き場はプラットフォームで違う
# （macOSは CafeOrders.app/Contents/MacOS/CafeOrders、Linuxは CafeOrders/bin/CafeOrders）。
launcher="$(find out/dist -type f -perm -u+x -name 'CafeOrders' 2>/dev/null | head -1)"
if [ -n "$launcher" ]; then
  if "$launcher" >out/launch.log 2>&1 && grep -q '^total=1584$' out/launch.log; then
    pass jpackage-runs '生成した配布物をそのまま起動でき、期待する出力になりました'
  else
    show '配布物の出力' out/launch.log
    bad jpackage-runs '生成した配布物を起動できません。入口のJARを --main-jar で指定してください'
  fi
else
  bad jpackage-runs '起動可能ファイル CafeOrders が生成物の中に見つかりません。--type と --name を確認してください（インストーラ形式ではなく、そのまま動く app-image を作ります）'
fi

# 版はapp-imageの中の情報から確かめる（置き場がプラットフォームで違うので全体から探す）
if [ -d out/dist ] && grep -rqs '2\.1\.0' out/dist; then
  pass jpackage-version '配布物へ版 2.1.0 を記録しました'
else
  bad jpackage-version '配布物の版を 2.1.0 として指定してください（--app-version）'
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'jshell -q --execution local -J-cp -Jout/classes --class-path out/classes exercise/probe.jsh'
printf '%s\n' 'jpackage --help の --type / --name / --main-jar / --app-version'
exit "$fail"
