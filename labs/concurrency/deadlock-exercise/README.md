# デッドロック調査 lab

動いているJVMからスレッドダンプを取り、循環待ちを読み取って、原因を直します。
第37章の「スレッドダンプとデッドロック」が採点に使います。

```
sh run-runtime-lab.sh
```

依存はありません。JDKだけで動きます（`javac`・`java`・`jcmd`）。

## 何をするか

1. `broken/DeadlockDemo.java`（参照専用）が必ずデッドロックします。採点はこれを動かし、
   `jcmd <pid> Thread.print` で**実物のスレッドダンプ**を取ります。
   詰まっている2スレッドの行は出力に出るので、それを読みます。
2. 読み取った事実を `exercise/diagnosis.properties` へ書きます。
3. `src/InventoryService.java` の取得順をそろえて、デッドロックを直します。

## なぜ「毎回必ず」起きるのか

デッドロックは普通、実行のタイミング次第で再現しません。それでは採点できないので、
この lab では2つの仕掛けで確定させています。

- `broken/DeadlockDemo.java` は `CountDownLatch` で「両方が1つ目のロックを持った状態」を
  作ってから2つ目へ進みます。
- `src/TableLock.java` はロック取得後に40ミリ秒だけ待ちます。2つの処理が1つ目のロックを
  同時に持つ状態が確実に作られるので、取得順が逆なら必ず詰まり、そろっていれば必ず通ります。

## ダンプの書式はJVMによって違う

同じデッドロックでも、`jcmd Thread.print` の出力はJVMの実装で変わります。

```text
HotSpot 系:
  "checkout-worker" ... java.lang.Thread.State: BLOCKED (on object monitor)
       - waiting to lock <0x...> (a DeadlockDemo$StockTableLock)
       - locked <0x...> (a DeadlockDemo$OrderTableLock)
  Found one Java-level deadlock: ...

OpenJ9 系:
  "checkout-worker" prio=5 Id=29 BLOCKED on DeadlockDemo$StockTableLock@... owned by "restock-worker"
```

`Found one Java-level deadlock` の節はHotSpot系にしか出ません。そのため採点は、
どちらにも出るもの（スレッド名・ロックのクラス名・`BLOCKED`）だけをダンプから確かめ、
**正解の値は `ThreadMXBean.findDeadlockedThreads()` で測ります**。JMXは仕様で決まっているので、
どのJVMでも同じ結果になります。

## 直し方

**ロックの取得順を1つに決める**のが基本です。すべての処理が同じ順番で取れば、
「AがBを待ち、BがAを待つ」という輪は作れません。順番はどちらでも構いません
（注文→在庫でも、在庫→注文でも通ります）。

ロックを外して速くするのは解決ではありません。注文数＋在庫数が変わらないことも採点します。

## ディレクトリ

```
broken/DeadlockDemo.java      必ずデッドロックするコード（参照専用・調査の題材）
src/TableLock.java            テーブル1つ分のロック（参照専用）
src/InventoryService.java     直す対象
src/CrossingCheck.java        3段の検証（参照専用）
exercise/diagnosis.properties ダンプから読み取った事実を書く
reference/                    模範解答
```
