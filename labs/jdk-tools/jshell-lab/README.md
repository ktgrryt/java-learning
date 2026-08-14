# jshell と jpackage の lab

配布前の確認を `jshell` で行い、配布物を `jpackage` で作ります。
第23章「JDKの選定・互換性・標準ツール」の `51-3` が採点に使います。

```
sh run-runtime-lab.sh
```

依存はありません。JDKだけで動きます（`javac`・`jar`・`jshell`・`jpackage`）。

## 何をするか

1. `exercise/probe.jsh` … `jshell` で `cafe.pricing.Pricing` の振る舞いを確かめます。
   値は環境変数（`JQ_PRICE`・`JQ_COUNT`・`JQ_ODD`）で渡されます。
   **採点は違う値で2回動かす**ので、定数を直接書くと2回目で外れます。
2. `exercise/package.options` … `jpackage` へ渡す指定を書きます。
   採点側は `jpackage --dest out/dist --input <JARの置き場>` のうしろへ、書いた指定を足して実行します。

## jshellをスクリプトとして使う

`jshell` は対話で使う道具として知られていますが、ファイルを渡すとそのまま評価します。
CIや調査手順に組み込めるのはこの形です。

```
jshell -q --execution local --class-path out/classes exercise/probe.jsh
```

- `-q` … 起動時の案内を出さない。出力の比較を邪魔しない
- `--execution local` … 別プロセスを起こさずに評価する。起動が速く、環境差も小さい
- 最後に `/exit` が無いと終わりません

## jpackageの生成物は置き場がプラットフォームで違う

`--type app-image` で作った配布物の起動可能ファイルは、次の場所にできます。

```text
macOS : dist/CafeOrders.app/Contents/MacOS/CafeOrders
Linux : dist/CafeOrders/bin/CafeOrders
```

この lab は名前で探すので、どちらでも同じように採点できます。
`dmg` や `deb` のようなインストーラ形式を選ぶと、そのままでは起動できないため落ちます。

`jpackage` はプラットフォーム側の道具に依存します。作れない環境では、採点側が要件確認の段で
実際に最小のapp-imageを作って確かめ、作れない場合は環境不足として省略します
（★や章クリアの判定には影響しません）。

## ディレクトリ

```
app/Pricing.java              確かめる対象（参照専用）
app/Main.java                 配布物の入口（参照専用）
exercise/probe.jsh            jshellで動かすスクリプト（編集する）
exercise/package.options      jpackageへ渡す指定（編集する）
reference/                    模範解答
```
