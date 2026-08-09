# JDK標準ツール観察ラボ

IDEを閉じる必要はありません。IDEが裏で扱っているclass、module、runtimeをCLIから確認します。

```bash
javac --release 21 -d out src/example.tools/example/tools/ToolDemo.java
java -cp out example.tools.ToolDemo
javap -classpath out -c -p example.tools.ToolDemo
jdeps --print-module-deps out
javadoc -d docs src/example.tools/example/tools/ToolDemo.java
```

module版としてcompileした後は、必要なmoduleだけのruntime imageも作れます。

```bash
javac --release 21 -d mods/example.tools src/example.tools/module-info.java src/example.tools/example/tools/ToolDemo.java
jlink --module-path "$JAVA_HOME/jmods:mods" --add-modules example.tools --output runtime
runtime/bin/java -m example.tools/example.tools.ToolDemo
```

`javap` の出力でsource上のloopや文字列連結がどのinstructionへ変換されたか、`jdeps` で
依存moduleが増える操作は何かを記録してください。配布時はlicense、timezone/charset data、
CA certificateなど、縮小runtimeに必要なものも確認します。

