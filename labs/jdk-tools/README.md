# JDK標準ツール観察ラボ

IDEを閉じる必要はありません。IDEが裏で扱っているclass、module、runtimeをCLIから確認します。

## 必要なもの

- JDK 21以降のみ（`javap` `jdeps` `javadoc` `jlink` はJDKに同梱）

> JDK 21.0.8 (IBM Semeru) / macOS 15 で動作確認。`jlink` は JDK 25.0.3 (IBM Semeru) /
> macOS 15 でも確認したが、後述のとおりJDK配布物によって失敗する。

## 教材の採点対象

第51章の演習は、このラボを自動採点へつないでいる。学習者が編集するのは次のファイルで、
`reference/` は模範解答、`run-runtime-lab.sh` は固定の採点スクリプトである。

| 課題 | 編集するファイル | 確認すること |
|---|---|---|
| 51-3 実機演習（必須） | `exercise/tools.options` `exercise/Menu.java` | `--release`で作った版、`javap`のmajor versionと命令、`jdeps`のmodule依存、実行結果 |
| 51-3 任意発展 | `jlink-lab/exercise/module-info.java` | `jlink`で作った縮小ランタイムのmodule一覧と起動 |

下の「手順」は同じ道具を手で動かすためのもので、採点とは独立している。

## 手順

```bash
javac --release 21 -d out src/example.tools/example/tools/ToolDemo.java
java -cp out example.tools.ToolDemo
javap -classpath out -c -p example.tools.ToolDemo
jdeps --print-module-deps out
javadoc -d docs src/example.tools/example/tools/ToolDemo.java
```

## 成功したらこう出る

```
$ java -cp out example.tools.ToolDemo
55

$ jdeps --print-module-deps out
java.base
```

`javap -c` はバイトコードを出します。`sumTo` はこう見えます。

```
  public static int sumTo(int);
    Code:
       0: iconst_1
       1: iload_0
       2: invokestatic  #7    // InterfaceMethod java/util/stream/IntStream.rangeClosed:(II)Ljava/util/stream/IntStream;
       5: invokeinterface #13,  1  // InterfaceMethod java/util/stream/IntStream.sum:()I
      10: ireturn
```

## 縮小ランタイム（jlink）

module版としてコンパイルすると、必要なモジュールだけのランタイムイメージを作れます。

```bash
javac --release 21 -d mods/example.tools src/example.tools/module-info.java src/example.tools/example/tools/ToolDemo.java
jlink --module-path "$JAVA_HOME/jmods:mods" --add-modules example.tools --output runtime
runtime/bin/java -m example.tools/example.tools.ToolDemo
```

うまくいくと `55` が出て、`runtime` は **46MB 程度**（JDK全体は370MB程度）になります。

> ⚠️ `jlink` はJDKによって失敗します。IBM Semeru 21 (OpenJ9) では
> `エラー: invalid section: __MACOSX` で止まりました（同じ手順が Semeru 25.0.3 では通ります）。
> HotSpot 系（Homebrewの `openjdk` など）でも通ります。`jlink` を試すときはJDKを替えてください。
> `jmods` を同梱しないJDKでも失敗します。だから教材側でも任意発展として分けています。

## 確認すること

1. `javap` の出力で、ソース上のループや文字列連結がどの命令へ変換されたかを記録する
2. `jdeps --print-module-deps` の結果が増えるのはどんな操作か（`java.base` 以外が出る
   コードを書いてみる）
3. 配布時はライセンス、タイムゾーン・文字セットのデータ、CA証明書など、縮小ランタイムに
   必要なものも確認する

生成物（`out/` `mods/` `runtime/` `docs/`）はコミットしません。
