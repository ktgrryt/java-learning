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
  for id in deadlock-dump diagnosis-threads diagnosis-locks fix-both-locks fix-crossing fix-invariant; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

answer() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" exercise/diagnosis.properties \
    | head -1 | tr -d '\015' | sed 's/[[:space:]]*$//'
}

fact() {
  sed -n "s/^FACT	$1	//p" out/broken.log | head -1
}

# ── 1. 必ずデッドロックするコードを動かし、実物のスレッドダンプを取る ──────────
if ! javac -encoding UTF-8 -d out/broken broken/DeadlockDemo.java >out/javac-broken.log 2>&1; then
  show 'compileの失敗' out/javac-broken.log
  fail_all '参照用コードをcompileできません。JDKの状態を確認してください'
fi

java -cp out/broken DeadlockDemo >out/broken.log 2>&1 &
broken_pid=$!
i=0
while [ "$i" -lt 200 ]; do
  grep -q 'DEADLOCK-READY' out/broken.log 2>/dev/null && break
  i=$((i + 1))
  sleep 0.1
done

if grep -q 'DEADLOCK-READY' out/broken.log 2>/dev/null; then
  jcmd "$broken_pid" Thread.print >out/thread-dump.txt 2>out/jcmd.log
  # 書式はJVM実装で違う（HotSpotとOpenJ9で節の作りが異なる）。
  # どちらにも出るもの＝スレッド名・ロックのクラス名・BLOCKED だけを当てにする。
  if grep -q 'checkout-worker' out/thread-dump.txt 2>/dev/null \
      && grep -q 'restock-worker' out/thread-dump.txt 2>/dev/null \
      && grep -q 'OrderTableLock' out/thread-dump.txt 2>/dev/null \
      && grep -q 'StockTableLock' out/thread-dump.txt 2>/dev/null \
      && grep -qi 'BLOCKED' out/thread-dump.txt 2>/dev/null; then
    pass deadlock-dump '動いているJVMからスレッドダンプを取り、詰まっている2スレッドと2つのロックを確認しました'
  else
    show 'jcmdの出力' out/jcmd.log
    bad deadlock-dump 'スレッドダンプに詰まっているスレッドが出ていません。jcmdが動く環境か確認してください'
  fi
else
  show '参照用コードの出力' out/broken.log
  bad deadlock-dump 'デッドロックを再現できませんでした'
fi
kill "$broken_pid" 2>/dev/null
wait "$broken_pid" 2>/dev/null

# 読む材料として、詰まっている2スレッドの行だけ出す（答えはダンプの中にある）
grep -E 'checkout-worker|restock-worker' out/thread-dump.txt 2>/dev/null >out/dump-excerpt.txt
show 'スレッドダンプ（詰まっている2スレッド）' out/dump-excerpt.txt

# ── 2. ダンプから読み取った事実が、JMXで測った事実と合っているか ──────────────
want_threads="$(fact 'blocked.threads')"
want_cycle="$(fact 'cycle.length')"
want_checkout="$(fact 'waits.checkout-worker')"
want_restock="$(fact 'waits.restock-worker')"

if [ -z "$want_threads" ] || [ -z "$want_checkout" ]; then
  bad diagnosis-threads '測定値を取得できませんでした（先の段が失敗しています）'
  bad diagnosis-locks '測定値を取得できませんでした（先の段が失敗しています）'
else
  got_threads="$(answer 'blocked\.threads')"
  got_cycle="$(answer 'cycle\.length')"
  if [ "$got_threads" = "$want_threads" ] && [ "$got_cycle" = "$want_cycle" ]; then
    pass diagnosis-threads '循環待ちに入っているスレッドと数が、実測と一致しました'
  else
    bad diagnosis-threads 'blocked.threadsとcycle.lengthを、ダンプに出ている2スレッドから埋めてください（辞書順・コンマ区切り）'
  fi

  got_checkout="$(answer 'waits\.checkout-worker')"
  got_restock="$(answer 'waits\.restock-worker')"
  if [ "$got_checkout" = "$want_checkout" ] && [ "$got_restock" = "$want_restock" ]; then
    pass diagnosis-locks '各スレッドが待っているロックが、実測と一致しました'
  else
    bad diagnosis-locks 'waits.* には「そのスレッドが取得を待っているロック」のクラス名を書いてください。すでに持っているロックではありません'
  fi
fi

# ── 3. 直したコードが、交差しても詰まらないか ─────────────────────────────
if ! javac -encoding UTF-8 -d out/fixed src/TableLock.java src/InventoryService.java \
    src/CrossingCheck.java >out/javac-fixed.log 2>&1; then
  show 'compileの失敗' out/javac-fixed.log
  bad fix-both-locks 'InventoryService.javaをcompileできません'
  bad fix-crossing '先にcompileを通してください'
  bad fix-invariant '先にcompileを通してください'
else
  java -cp out/fixed CrossingCheck >out/fixed.log 2>&1
  both="$(sed -n 's/^RESULT	both-locks	//p' out/fixed.log | head -1)"
  crossing="$(sed -n 's/^RESULT	crossing	//p' out/fixed.log | head -1)"
  invariant="$(sed -n 's/^RESULT	invariant	//p' out/fixed.log | head -1)"

  case "$both" in
    OK) pass fix-both-locks 'checkoutとrestockが、どちらも2つのロックを取っています' ;;
    MISSING*) bad fix-both-locks "片方のロックしか取っていません（${both}）。両方のテーブルを触る処理なので、両方のロックを取ってください" ;;
    *) show '検証の出力' out/fixed.log
       bad fix-both-locks 'ロックの取得回数を確認できませんでした' ;;
  esac

  case "$crossing" in
    OK) pass fix-crossing 'checkoutとrestockが同時に動いても、どちらも完了しました' ;;
    DEADLOCK) bad fix-crossing '循環待ちが起きています。2つの処理でロックの取得順をそろえてください' ;;
    STUCK) bad fix-crossing '処理が終わりません。取ったロックを必ず解放しているか確認してください' ;;
    '') bad fix-crossing '先に両方のロックを取ってください' ;;
    *) show '検証の出力' out/fixed.log
       bad fix-crossing '交差の検証を実行できませんでした' ;;
  esac

  case "$invariant" in
    OK) pass fix-invariant '多数回動かしても注文数＋在庫数が変わりませんでした' ;;
    '') bad fix-invariant '先に交差の検証を通してください' ;;
    *) bad fix-invariant "注文数＋在庫数が変わりました（${invariant}）。ロックを外して速くするのは解決ではありません" ;;
  esac
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'ダンプの行の「BLOCKED on ... owned by ...」または「waiting to lock ... / - locked ...」'
printf '%s\n' 'src/InventoryService.java の checkout と restock が、どちらのロックを先に取っているか'
exit "$fail"
