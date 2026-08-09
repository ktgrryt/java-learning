# JVM診断ラボ

すべてJDK 21だけで動きます。練習用プロセスに対して実行してください。

## スレッドダンプ

```sh
javac DeadlockDemo.java
java DeadlockDemo
```

別ターミナルで、表示されたPIDを使います。

```sh
jcmd <pid> Thread.print
jstack <pid>
```

`Found one Java-level deadlock` と、互いのロックを待つ2スレッドを探します。
終了はCtrl+Cです。

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

生成した`lab.jfr`をJDK Mission Controlで開き、Allocation、GC、Method Profilingを
確認します。記録ファイルは共有前に機密情報の有無を確認してください。

GCログも試せます。

```sh
java -Xms64m -Xmx64m -Xlog:gc*:file=gc.log:time,uptime,level,tags AllocationDemo
```
