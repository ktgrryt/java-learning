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
  for id in race-lost-update race-invariant race-oversell; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

value() {
  sed -n "s/^RESULT	$1	//p" out/harness.log | head -1
}

if ! javac -encoding UTF-8 --release 21 -d out/classes \
    RaceHarness.java exercise/StockCounter.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'StockCounter.javaをcompileできません'
fi

if ! java -cp out/classes RaceHarness >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all '8スレッドでの実行が完了しませんでした'
fi

rounds="$(value rounds)"
stock="$(value stock)"
if [ -z "$rounds" ] || [ -z "$stock" ]; then
  show '実行の出力' out/harness.log
  fail_all '測定値を取得できませんでした'
fi

printf '%s\n' "--- 実測（${rounds}回戦・8スレッド × 50,000回・在庫${stock}）---"
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

lost_rounds="$(value lost-rounds)"
lost_total="$(value lost-total)"
lost_detail="$(value lost-detail)"
if [ "$lost_rounds" = 0 ]; then
  pass race-lost-update "${rounds}回戦すべてで、成功した出荷の回数と集計が一致しました"
else
  bad race-lost-update "${rounds}回戦のうち${lost_rounds}回で集計が合いません（取りこぼし計${lost_total}件）。${lost_detail}"
fi

torn_rounds="$(value torn-rounds)"
torn_total="$(value torn-total)"
torn_detail="$(value torn-detail)"
if [ "$torn_rounds" = 0 ]; then
  pass race-invariant "別スレッドから何度覗いても、在庫＋出荷済みが${stock}のままでした"
else
  bad race-invariant "${torn_rounds}回で更新途中の状態が見えました（計${torn_total}回）。${torn_detail}"
fi

oversell_rounds="$(value oversell-rounds)"
oversell_total="$(value oversell-total)"
oversell_detail="$(value oversell-detail)"
if [ "$oversell_rounds" = 0 ]; then
  pass race-oversell "在庫${stock}を超える出荷は1件もありませんでした（足りない要求は断られました）"
else
  bad race-oversell "${oversell_rounds}回で在庫を超えて出荷しました（計${oversell_total}件）。${oversell_detail}"
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'ship() の「在庫を確認してから減らす」の間に、他のスレッドが同じ確認を通れないか'
printf '%s\n' 'snapshot() が2つの値を読む間に、他のスレッドが片方だけ更新できないか'
exit "$fail"
