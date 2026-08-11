# JVM診断ラボ

すべてJDK 21だけで動きます。練習用プロセスに対して実行してください。

## 必要なもの

- JDK 21以降のみ（`jcmd` `jstack` はJDKに同梱）
- ターミナル2つ
- JFRの記録を見るときだけ JDK Mission Control（別途入手）

> JDK 21.0.8 (IBM Semeru) / macOS 15 で動作確認

## スレッドダンプ

```sh
javac DeadlockDemo.java
java DeadlockDemo
```

起動すると `pid=<番号>` を表示して止まったままになります（これがデッドロックです）。
別のターミナルでそのPIDを使います。

```sh
jcmd <pid> Thread.print
jstack <pid>
```

### 成功したらこう出る

HotSpot系のJDKでは、ダンプの中にこの行が出ます。

```
Found one Java-level deadlock:
```

> ⚠️ この行は **HotSpotの書き方** です。IBM Semeru (OpenJ9) では出ません。
> どのJDKでも読めるのは、2つのスレッドが互いを待っている次の形です。
>
> ```
> "left-first-second"  ... BLOCKED on java.lang.Object@6f5e40d7 owned by "right-second-first"
> "right-second-first" ... BLOCKED on java.lang.Object@e8b6ca38 owned by "left-first-second"
> ```
>
> `owned by` の相手をたどると輪になっていれば、デッドロックです。
> OpenJ9では `jcmd <pid> Dump.java` で javacore を取る方法もあります。

終了は `Ctrl+C` です。

## JFRと割り当て

```sh
javac AllocationDemo.java
java AllocationDemo
```

別ターミナルで60秒以内に記録します。

```sh
jcmd <pid> JFR.start name=lab duration=20s filename=lab.jfr settings=profile
jcmd <pid> JFR.check
```

`JFR.start` は記録の開始と出力先を返し、20秒後に `lab.jfr` ができます。

> ⚠️ `JFR.check` は **HotSpotのコマンド** です。IBM Semeru (OpenJ9) では
> `Command JFR.check not recognized` になります（`JFR.start` は使えます）。
> どのJDKでも使えるのは、記録が終わったあとにファイルを直接見る方法です。
>
> ```sh
> jfr summary lab.jfr
> ```
>
> ```
>  Event Type                     Count  Size (bytes)
> ===================================================
>  jdk.ExecutionSample              479         10402
>  jdk.ThreadSleep                  433         11170
> ```
>
> どのイベントが何件記録されたかが分かるので、記録の設定が意図どおりかを確かめられます。

生成した `lab.jfr` を JDK Mission Control で開き、Allocation、GC、Method Profiling を
確認します。記録ファイルは共有前に機密情報の有無を確認してください。

## GCログ

```sh
java -Xms64m -Xmx64m -Xlog:gc*:file=gc.log:time,uptime,level,tags AllocationDemo
```

`gc.log` に `Pause Young` などの行が積まれます。**回収後の使用量が下がっているか**を
見ます（一瞬の高使用率だけではリークの判断はできません）。

生成物（`*.class` `lab.jfr` `gc.log`）はコミットしません。
