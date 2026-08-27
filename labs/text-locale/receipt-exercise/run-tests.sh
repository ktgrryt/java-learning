#!/usr/bin/env bash
#
# 同じ .class を「3つの環境」で走らせ、出力が変わらないことを確かめる。
#
#   ./run-tests.sh            … src/ をそのまま検査する（画面から提出したときと同じ）
#   ./run-tests.sh reference  … 模範解答を src/ へ重ねて検査する
#
# ロケールは `java -Duser.language=… -Duser.country=…` で切り替える。
# **明示していない書き方は、ここで必ず落ちる** ―― それがこの演習の全部である。
set -uo pipefail

LAB_DIR="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-app}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -R "$LAB_DIR/src" "$WORK/src"
if [[ "$VARIANT" == "reference" ]]; then
  cp -R "$LAB_DIR/reference/src/." "$WORK/src/"
fi

expected='2026/01/01(木) ISHIDA 1,234,567円
2026/05/03(日) KIMURA 980円'

fail=0
pass() { printf '  OK   %s\n' "$1"; }
bad()  { printf '  NG   %s\n' "$1"; fail=1; }

run_in() {   # 言語 国
  (cd "$WORK" && java -Duser.language="$1" -Duser.country="$2" -cp out cafe.report.Main 2>&1)
}

# 1. コンパイルできる
if javac -encoding UTF-8 -d "$WORK/out" $(find "$WORK/src" -name '*.java') > "$WORK/javac.log" 2>&1; then
  pass 'コンパイルできました'
else
  bad 'コンパイルできません。javac の1件目のエラーを読んでください'
  sed 's/^/       /' "$WORK/javac.log" | head -10
fi

if [[ -d "$WORK/out" ]]; then
  # 2. 日本の環境で期待どおり
  ja="$(run_in ja JP)"
  if [[ "$ja" == "$expected" ]]; then
    pass '日本語の環境（ja_JP）で期待どおりの2行です'
  else
    bad '日本語の環境で出力が違います'
    printf '       期待:\n%s\n       実際:\n%s\n' "$(printf '%s' "$expected" | sed 's/^/         /')" \
      "$(printf '%s' "$ja" | sed 's/^/         /')"
  fi

  # 3〜4. 別の環境でも「同じ」であること
  same_in() {
    local label="$1" out
    out="$(run_in "$2" "$3")"
    if [[ "$out" == "$expected" ]]; then
      pass "${label}でも同じ2行です"
    else
      bad "${label}で出力が変わりました（明示していないところがあります）"
      printf '%s\n' "$out" | sed 's/^/         /' | head -4
    fi
  }
  same_in '英語の環境（en_US）' en US
  same_in 'トルコ語の環境（tr_TR）' tr TR

  # 参考（採点外）: 何も明示しない書き方は、環境ごとに出力が変わる
  printf '  --   参考: 何も明示しない書き方（Loose）を同じ3環境で走らせた結果\n'
  for e in "ja JP" "en US" "tr TR"; do
    set -- $e
    (cd "$WORK" && java -Duser.language="$1" -Duser.country="$2" \
      -cp out cafe.report.Loose 2>&1) | sed 's/^/       /'
  done
fi

echo
if [[ "$fail" -eq 0 ]]; then
  echo '3つのロケールすべてで同じ出力になりました。'
  exit 0
fi
echo 'ロケールによって結果が変わっています。上の NG を直してください。'
exit 1
