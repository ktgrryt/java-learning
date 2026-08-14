#!/usr/bin/env sh
set -u

# 生成物はすべてout/へ置く。out/は教材側で表示・コピー対象から外れている。
rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

# ツールの出力をそのまま流すと、学習者が書いた行が検査結果として読まれ得る。
# 行頭の印だけ無効化して、読みやすさは保つ。
show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

# キーの前後や = のまわりに空白があっても読めるようにする。
opt() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" exercise/tools.options \
    | head -1 | tr -d ' \011\015'
}

release="$(opt 'compile\.release')"
bytecode_tool="$(opt 'bytecode\.tool')"
bytecode_flag="$(opt 'bytecode\.flag')"
modules_tool="$(opt 'modules\.tool')"
modules_flag="$(opt 'modules\.flag')"

# 書かれた値をそのまま実行はしない。この章で扱う語彙だけに限る。
# ただし「どの道具がどの目的に効くか」は限定しない。それは出力で分かる。
tool_or_empty() {
  case "$1" in javap|jdeps|jdeprscan|jshell|jlink|jpackage|javadoc) printf '%s' "$1" ;; esac
}
flag_or_empty() {
  case "$1" in
    -v|-verbose|-c|-p|-l|-s|-summary|--print-module-deps|--list-deps|--check|--dot-output)
      printf '%s' "$1" ;;
  esac
}
case "$release" in 8|11|17|21|22|23|24|25) ;; *) release='' ;; esac
bytecode_tool="$(tool_or_empty "$bytecode_tool")"
modules_tool="$(tool_or_empty "$modules_tool")"
bytecode_flag="$(flag_or_empty "$bytecode_flag")"
modules_flag="$(flag_or_empty "$modules_flag")"

# 1. 本番のJVMへ向けてclass fileを作る
compiled=0
if [ -z "$release" ]; then
  bad jdk-release-target 'compile.releaseへ、この演習が対象とする本番JVMの版を書いてください'
elif javac --release "$release" -d out/classes exercise/Menu.java >out/javac.log 2>&1 \
    && [ -f out/classes/example/tools/Menu.class ]; then
  compiled=1
  pass jdk-release-target "javac --release $release でclass fileを作りました"
else
  bad jdk-release-target "javac --release $release が失敗しました。下のjavacの出力を読んでください"
fi

# 2. 同じsourceが古い版では通らないこと（sourceの互換性）
if [ "$compiled" -eq 0 ]; then
  bad jdk-source-compat '先に本番の版へ向けたcompileを通してください'
elif javac --release 17 -d out/classes17 exercise/Menu.java >out/javac17.log 2>&1; then
  bad jdk-source-compat 'Java 21で追加されたAPIを使い、--release 17では通らないsourceにしてください'
else
  pass jdk-source-compat '同じsourceが--release 17では通らないことを確認しました'
fi

# 3・4. class fileの版と、連結が変換された命令を読む
bytecode_ran=0
if [ "$compiled" -eq 1 ] && [ -n "$bytecode_tool" ] && [ -n "$bytecode_flag" ]; then
  "$bytecode_tool" "$bytecode_flag" -cp out/classes example.tools.Menu >out/bytecode.txt 2>&1
  bytecode_ran=1
fi
if [ "$bytecode_ran" -eq 1 ] && grep -Fq 'major version: 65' out/bytecode.txt; then
  pass jdk-class-version 'class fileがJava 21向け（major version 65）だと読み取れました'
elif [ "$bytecode_ran" -eq 1 ]; then
  bad jdk-class-version 'class fileの版を出す道具と指定を選び、本番と同じ版へ向けて作ってください'
else
  bad jdk-class-version 'bytecode.tool・bytecode.flagにJDK付属ツールの指定を書いてください'
fi
if [ "$bytecode_ran" -eq 1 ] && grep -Fq 'makeConcatWithConstants' out/bytecode.txt; then
  pass jdk-bytecode-concat '文字列連結がinvokedynamicへ変換されていることを読み取りました'
else
  bad jdk-bytecode-concat '逆アセンブルまで出す指定にし、+による連結をsourceへ残してください'
fi

# 5. 実行に必要なJDK moduleを一覧する
deps=''
if [ "$compiled" -eq 1 ] && [ -n "$modules_tool" ] && [ -n "$modules_flag" ]; then
  "$modules_tool" "$modules_flag" out/classes >out/modules.txt 2>&1
  deps="$(tr -d ' \011\015' <out/modules.txt | tr '\n' ' ' | sed 's/ *$//')"
fi
case "$deps" in
  java.base,java.net.http|java.net.http,java.base)
    pass jdk-module-deps '必要moduleがjava.baseとjava.net.httpだと一覧できました' ;;
  *)
    bad jdk-module-deps '依存moduleだけを一覧する指定にし、java.net.httpを使うsourceにしてください' ;;
esac

# 6. 作ったclassが実際にJVMで動く
expected="$(printf 'first=espresso last=mocha\npath=/menu\n')"
if [ "$compiled" -eq 1 ]; then
  java -cp out/classes example.tools.Menu >out/run.txt 2>&1
fi
if [ "$compiled" -eq 1 ] && [ "$(cat out/run.txt 2>/dev/null)" = "$expected" ]; then
  pass jdk-run 'class fileをJVMで実行し、期待した2行を確認しました'
else
  bad jdk-run 'first=espresso last=mocha と path=/menu の2行を出力してください'
fi

show 'javac (本番の版)' out/javac.log
show 'javac --release 17' out/javac17.log
grep -E 'major version|makeConcatWithConstants' out/bytecode.txt 2>/dev/null \
  | head -4 | sed 's/^JQ_CHECK/JQ-CHECK/'
show 'module依存' out/modules.txt
show '実行結果' out/run.txt
exit "$fail"
