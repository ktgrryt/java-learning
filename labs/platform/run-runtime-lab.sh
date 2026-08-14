#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

fail_all() {
  for id in platform-default platform-locale platform-timezone platform-charset platform-input; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

# 1回だけコンパイルする。以降の実行はすべて同じclass fileを使う。
if ! javac --release 21 -d out/classes exercise/OrderReport.java >out/javac.log 2>&1; then
  # 学習者のコードがそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
  sed 's/^JQ_CHECK/JQ-CHECK/' out/javac.log
  fail_all 'コンパイルできません。下のjavacの出力を読んでください'
fi

expected="$(printf 'code=ID\ntotal=1,234.50\nrecorded=2026-08-13\nlabel=café\n')"
expected_alt="$(printf 'code=SKU\ntotal=98,765.40\nrecorded=2026-01-01\nlabel=naïve\n')"

# 同じclass fileを、環境の既定値だけ変えて動かす。
run() {
  shift_args="$1"
  data="$2"
  case "$shift_args" in
    default) java -cp out/classes OrderReport "$data" 2>&1 ;;
    locale) java -Duser.language=tr -Duser.country=TR -cp out/classes OrderReport "$data" 2>&1 ;;
    timezone) java -Duser.timezone=UTC -cp out/classes OrderReport "$data" 2>&1 ;;
    charset) java -Dfile.encoding=ISO-8859-1 -Dstdout.encoding=UTF-8 \
        -cp out/classes OrderReport "$data" 2>&1 ;;
  esac
}

# 検査結果の書式を壊さないよう、実測値は1行へ詰めて短く出す。
oneline() { printf '%s' "$1" | tr '\n\t' '  ' | cut -c1-110; }

# 変数の直後に日本語が続くと、shellが変数名へバイトを巻き込む。必ず${}で閉じる。
check() {
  id="$1"; mode="$2"; want="$3"; data="$4"; label="$5"
  got="$(run "$mode" "$data")"
  if [ "$got" = "$want" ]; then
    pass "$id" "${label}: 期待した4行になりました"
  else
    bad "$id" "${label}: 出力が違います（実際: $(oneline "$got")）"
  fi
}

check platform-default default "$expected" data/orders.txt '既定の環境'
check platform-locale locale "$expected" data/orders.txt 'ロケールをtr_TRにした場合'
check platform-timezone timezone "$expected" data/orders.txt 'タイムゾーンをUTCにした場合'
check platform-charset charset "$expected" data/orders.txt '既定の文字集合をISO-8859-1にした場合'
# 定数を出しているだけでは通らないよう、別のデータでも確かめる。
check platform-input default "$expected_alt" data/orders-alt.txt '別のデータ（orders-alt.txt）'

printf '%s\n' '--- 期待する出力（data/orders.txt） ---'
printf '%s\n' "$expected"
exit "$fail"
