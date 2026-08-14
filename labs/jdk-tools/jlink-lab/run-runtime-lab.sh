#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out/mods/example.tools

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }
show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

# jlinkはJDK同梱のJMODから縮小ランタイムを組み立てる。JMODを含まない配布物では作れない。
jdk_home=''
javac_path="$(command -v javac 2>/dev/null || true)"
if [ -n "$javac_path" ]; then
  jdk_home="$(cd "$(dirname "$javac_path")/.." 2>/dev/null && pwd)"
fi
if [ -z "$jdk_home" ] || [ ! -d "$jdk_home/jmods" ]; then
  message='このJDKにはjmodsが無いため縮小ランタイムを作れません。任意課題なので★には影響しません'
  bad jlink-module-compile "$message"
  bad jlink-image "$message"
  bad jlink-modules "$message"
  bad jlink-run "$message"
  exit 1
fi

# 1. moduleとしてcompileする（依存の宣言が足りなければここで止まる）
compiled=0
if javac --release 21 -d out/mods/example.tools \
    exercise/module-info.java src/example/tools/Menu.java >out/javac.log 2>&1; then
  compiled=1
  pass jlink-module-compile 'module宣言つきでcompileできました'
else
  bad jlink-module-compile 'module-info.javaへ、Menu.javaが使うJDK moduleを宣言してください'
fi

# 2. 縮小ランタイムを組み立てる
linked=0
if [ "$compiled" -eq 1 ] \
    && jlink --module-path "$jdk_home/jmods:out/mods" --add-modules example.tools \
        --output out/runtime >out/jlink.log 2>&1 \
    && [ -x out/runtime/bin/java ]; then
  linked=1
  pass jlink-image 'jlinkでこのmodule専用のランタイムimageを作りました'
else
  bad jlink-image 'jlinkがimageを作れませんでした。下のjlinkの出力を読んでください'
fi

# 3. imageへ入ったmoduleを確かめる（JDK全部ではないこと）
if [ "$linked" -eq 1 ]; then
  out/runtime/bin/java --list-modules >out/modules.txt 2>&1
fi
if [ "$linked" -eq 1 ] \
    && grep -q '^example.tools' out/modules.txt \
    && grep -q '^java.base' out/modules.txt \
    && grep -q '^java.net.http' out/modules.txt \
    && ! grep -qE '^(java.sql|java.desktop|java.xml|jdk.compiler)' out/modules.txt; then
  pass jlink-modules 'imageにexample.tools・java.base・java.net.httpだけが入りました'
else
  bad jlink-modules '宣言した依存だけがimageへ入るようにしてください（余分なmoduleは要りません）'
fi

# 4. 縮小ランタイムだけでprogramが動く
expected="$(printf 'first=espresso last=mocha\npath=/menu\n')"
if [ "$linked" -eq 1 ]; then
  out/runtime/bin/java -m example.tools/example.tools.Menu >out/run.txt 2>&1
fi
if [ "$linked" -eq 1 ] && [ "$(cat out/run.txt 2>/dev/null)" = "$expected" ]; then
  pass jlink-run '縮小ランタイムのjavaだけでprogramが動きました'
else
  bad jlink-run '出来たimageのbin/javaで -m 起動し、期待した2行を出してください'
fi

show 'javac' out/javac.log
show 'jlink' out/jlink.log
show 'imageのmodule一覧' out/modules.txt
show '実行結果' out/run.txt
exit "$fail"
