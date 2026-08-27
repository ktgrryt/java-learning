#!/usr/bin/env bash
#
# モジュールの境界を、javac だけで確かめる。
#
#   ./run-tests.sh            … src/ をそのまま検査する（画面から提出したときと同じ）
#   ./run-tests.sh reference  … 模範解答の module-info を重ねて検査する
#
# 見るのは3つ。**1つは「コンパイルできないこと」**を確かめる ――
# 公開範囲は動かして確かめられないので（見えなければコンパイルが止まる）、
# 通ってはいけないコードを probe-internal に置き、それが失敗することを検査にしている。
set -uo pipefail

LAB_DIR="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-app}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -R "$LAB_DIR/src" "$WORK/src"
if [[ "$VARIANT" == "reference" ]]; then
  cp -R "$LAB_DIR/reference/src/." "$WORK/src/"
fi

expected='☕ いらっしゃいませ、田中さん
☕ いらっしゃいませ、佐藤さん'

fail=0
pass() { printf '  OK   %s\n' "$1"; }
bad()  { printf '  NG   %s\n' "$1"; fail=1; }

# 参考（採点には含めない）: module-info を読まなければ、同じソースはクラスパスでも動く。
# 教材の「module-info.java を置かなければ従来どおりクラスパスで動く」がこれである。
if javac -encoding UTF-8 -d "$WORK/out-cp" \
    $(find "$WORK/src" -name '*.java' ! -name 'module-info.java') > "$WORK/cp.log" 2>&1 \
    && [[ "$(cd "$WORK" && java -cp out-cp cafe.app.Main 2>&1)" == "$expected" ]]; then
  printf '  --   参考: module-info を読まずにクラスパスで動かすと、いまのままでも動きます\n'
  printf '            （モジュールは必須ではありません。ここは採点に含めません）\n'
fi

# 1. 2つのモジュールをまとめてコンパイルできる（exports と requires がそろっている）
if javac -encoding UTF-8 -d "$WORK/out" --module-source-path "$WORK/src" \
    -m cafe.core,cafe.app > "$WORK/javac.log" 2>&1; then
  pass '2つのモジュールをモジュールパスでコンパイルできました'
else
  bad 'コンパイルできません。使うモジュールを requires で、使わせるパッケージを exports で宣言してください'
  sed 's/^/       /' "$WORK/javac.log" | head -10
fi

# 2. モジュールとして実行できて、出力が期待どおり
if [[ -d "$WORK/out" ]]; then
  actual="$(cd "$WORK" && java --module-path out -m cafe.app/cafe.app.Main 2>&1)"
  if [[ "$actual" == "$expected" ]]; then
    pass 'モジュールとして実行した出力が期待どおりです'
  else
    bad 'モジュールとして実行できません（または出力が違います）'
    printf '%s\n' "$actual" | sed 's/^/       /' | head -6
  fi
fi

# 3. 公開していないパッケージは、モジュールの外から使えない
if [[ -d "$WORK/out" ]]; then
  if javac -encoding UTF-8 --module-path "$WORK/out" --add-modules cafe.core \
      -d "$WORK/probe-out" $(find "$LAB_DIR/probe-internal" -name '*.java') \
      > "$WORK/probe.log" 2>&1; then
    bad 'モジュールの外から cafe.core.internal を使えてしまいます。exports するのは cafe.core だけです'
  else
    pass 'probe-internal は期待どおりコンパイルできません（公開していないパッケージは外から見えない）'
  fi
fi

echo
if [[ "$fail" -eq 0 ]]; then
  echo 'コンパイル・実行・公開範囲のすべてが期待どおりです。'
  exit 0
fi
echo '期待と違うところがあります。上の NG を1つずつ直してください。'
exit 1
