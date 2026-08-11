# JPMS・JARラボ

2つのモジュールをJDKだけでコンパイルし、JARへまとめます。`exports` を外すと
公開境界がコンパイル時に守られることまで確かめます。

## 必要なもの

- JDK 21以降のみ（Mavenもネットワークも不要）

> JDK 21.0.8 (IBM Semeru) / macOS 15 で動作確認

## 手順

このディレクトリで実行します。

```sh
javac -d out --module-source-path src -m cafe.greeting,cafe.app
jar --create --file cafe.greeting.jar -C out/cafe.greeting .
jar --create --file cafe.app.jar --main-class cafe.app.Main -C out/cafe.app .
java --module-path cafe.greeting.jar:cafe.app.jar -m cafe.app
```

Windowsではmodule-pathの区切りを `;` にします。

## 成功したらこう出る

```
Hello, Java!
```

## 確認すること

`cafe.greeting/module-info.java` から `exports cafe.greeting;` を一時的に外し、
もう一度コンパイルしてください。次のように **コンパイルの時点で** 止まります。

```
src/cafe.app/cafe/app/Main.java:3: エラー: パッケージcafe.greetingは表示不可です
import cafe.greeting.Greeter;
           ^
  (パッケージcafe.greetingはモジュールcafe.greetingで宣言されていますが、エクスポートされていません)
```

クラスパスに置いただけなら通ってしまう依存が、モジュールでは宣言しないと使えません。
確かめたら `exports` を戻してください。

生成物（`out/` と `*.jar`）はコミットしません。
