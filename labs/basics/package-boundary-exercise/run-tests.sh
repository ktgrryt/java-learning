#!/usr/bin/env bash
#
# パッケージの境界を、javac だけで確かめる。
#
#   ./run-tests.sh            … src/ をそのまま検査する（画面から提出したときと同じ）
#   ./run-tests.sh reference  … 模範解答を src/ へ重ねて検査する
#
# 見るのは6つで、**4つは「コンパイルできないこと」**を確かめる。
# 見える範囲は動かして確かめられないので（見えなければコンパイルが止まる）、
# 通ってはいけないコードを probe-* に置き、それが失敗することを検査にしている。
set -uo pipefail

LAB_DIR="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-app}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -R "$LAB_DIR/src" "$WORK/src"
if [[ "$VARIANT" == "reference" ]]; then
  cp -R "$LAB_DIR/reference/src/." "$WORK/src/"
fi

fail=0
pass() { printf '  OK   %s\n' "$1"; }
bad()  { printf '  NG   %s\n' "$1"; fail=1; }

# 1. src をまとめてコンパイルできる（=「見せる」と決めたものが足りている）
if javac -encoding UTF-8 -d "$WORK/out" $(find "$WORK/src" -name '*.java') > "$WORK/javac.log" 2>&1; then
  pass 'src の3ファイルをまとめてコンパイルできました'
else
  bad 'コンパイルできません。javac の1件目のエラーを読んでください'
  sed 's/^/       /' "$WORK/javac.log" | head -12
fi

# 2. 実行した出力が期待どおり（アクセサ・在庫の減り方・表示用の1行）
if [[ -d "$WORK/out" ]]; then
  expected='コーヒー 10
コーヒー 7
春の さくらラテ x5
春の さくらラテ x0'
  actual="$(cd "$WORK" && java -cp out Main 2>&1)"
  if [[ "$actual" == "$expected" ]]; then
    pass '実行した出力が期待どおりです'
  else
    bad '出力が違います'
    printf '       期待:\n%s\n       実際:\n%s\n' "$(printf '%s' "$expected" | sed 's/^/         /')" \
      "$(printf '%s' "$actual" | sed 's/^/         /')"
  fi
fi

# 3〜5. 通ってはいけないコードが、期待どおりコンパイルできないこと
must_not_compile() {
  local label="$1" dir="$2" why="$3"
  if javac -encoding UTF-8 -cp "$WORK/out" -d "$WORK/probe-out" \
      $(find "$LAB_DIR/$dir" -name '*.java') > "$WORK/$label.log" 2>&1; then
    bad "$why"
  else
    pass "$label は期待どおりコンパイルできません"
  fi
}

if [[ -d "$WORK/out" ]]; then
  must_not_compile 'probe-package-private' 'probe-package-private' \
    '別パッケージのクラスから在庫を減らせてしまいます。同じパッケージの中だけに閉じてください'
  must_not_compile 'probe-subclass' 'probe-subclass' \
    '別パッケージのサブクラスから在庫を減らせてしまいます。protected では広すぎます'
  must_not_compile 'probe-protected' 'probe-protected' \
    'サブクラスでない別パッケージのクラスから表示用の1行を呼べてしまいます。public では広すぎます'
  must_not_compile 'probe-field' 'probe-field' \
    '同じパッケージの別クラスからフィールドを直接読めてしまいます。フィールドは private にしてください'
fi

echo
if [[ "$fail" -eq 0 ]]; then
  echo 'コンパイル・実行・4つの境界すべてが期待どおりです。'
  exit 0
fi
echo '境界が期待と違います。上の NG を1つずつ直してください。'
exit 1
