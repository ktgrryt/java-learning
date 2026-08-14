#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out/classes

fail_all() {
  message="$1"
  for id in heap-retained heap-evicted-collectable cache-window stack-deep-input; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$message"
  done
  exit 1
}

if ! javac --release 21 -d out/classes \
    exercise/RecentOrders.java exercise/OrderTotals.java HeapProbe.java \
    >out/javac.log 2>&1; then
  # 学習者のコードがそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
  sed 's/^JQ_CHECK/JQ-CHECK/' out/javac.log
  fail_all 'コンパイルできません。下のjavacの出力を読んでください'
fi

# ヒープの上限を小さく固定して測る。GCログはJVM実装で形式が違うので使わず、
# 標準のJMXから使用量を読む。ヒープを使い切らせないので、ダンプも出ない。
java -Xmx128m -cp out/classes HeapProbe 2>out/probe-error.log
status=$?

if [ -s out/probe-error.log ]; then
  printf '%s\n' '--- 実行時の出力 ---'
  sed 's/^JQ_CHECK/JQ-CHECK/' out/probe-error.log | head -20
fi
exit "$status"
