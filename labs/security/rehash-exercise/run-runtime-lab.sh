#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out/classes

fail_all() {
  message="$1"
  for id in hash-format hash-salted hash-verify hash-legacy-verify hash-needs-rehash; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$message"
  done
  exit 1
}

if ! javac --release 21 -d out/classes exercise/PasswordHasher.java HasherProbe.java \
    >out/javac.log 2>&1; then
  # 学習者のコードがそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
  sed 's/^JQ_CHECK/JQ-CHECK/' out/javac.log
  fail_all 'PasswordHasherをコンパイルできません。下のjavacの出力を読んでください'
fi

# 検査結果はHasherProbeが出す。学習者の出力が混ざらないよう、標準エラーは分けて後で見せる。
java -cp out/classes HasherProbe 2>out/probe-error.log
status=$?

if [ -s out/probe-error.log ]; then
  printf '%s\n' '--- 実行時の出力 ---'
  sed 's/^JQ_CHECK/JQ-CHECK/' out/probe-error.log | head -20
fi
exit "$status"
