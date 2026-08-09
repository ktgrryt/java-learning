# JPMS・JARラボ

2つのモジュールをJDKだけでコンパイルし、JARへまとめます。このディレクトリで実行します。

```sh
javac -d out --module-source-path src -m cafe.greeting,cafe.app
jar --create --file cafe.greeting.jar -C out/cafe.greeting .
jar --create --file cafe.app.jar --main-class cafe.app.Main -C out/cafe.app .
java --module-path cafe.greeting.jar:cafe.app.jar -m cafe.app
```

Windowsではmodule-pathの区切りを`;`にします。次に`cafe.greeting/module-info.java`から
`exports`を一時的に外し、公開境界がコンパイル時に守られることを確認してください。
