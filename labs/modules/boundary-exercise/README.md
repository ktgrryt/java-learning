# モジュール境界演習（第29章 45-4の採点対象）

「JARに入っているのに、他のモジュールからは使えない」を実際に作る演習です。必要なのはJDKだけで、
Mavenもネットワークも要りません。

編集するのは2つの `module-info.java` で、`reference/` が模範解答です。Javaのソースは変更しません。

| ファイル | やること |
|---|---|
| `src/cafe.greeting/module-info.java` | 利用側が使うpackageだけを公開する |
| `src/cafe.app/module-info.java` | 使うmoduleを宣言する |

`cafe.greeting` には2つのpackageがあります。

```text
cafe.greeting            公開するAPI（Greeter）
cafe.greeting.internal   内部実装（Formatter）。moduleの外へ出さない
```

## 採点で何が起きるか

| 見るところ | 直っていないとき | 直ったとき |
|---|---|---|
| `javac --module-source-path` | `requires` が無くcompileできない | 2 moduleが通る |
| 別moduleから公開APIを使う | 使えない | compileできる |
| 別moduleから内部packageを使う | **使えてしまう** | compileできない |
| `java --module-path ... -m cafe.app` | 起動できない | `Hello, Java!` |
| `jar --describe-module` | `exports cafe.greeting.internal` が出る | 公開packageだけ |

3行目が要点です。`exports` を2つ書けばcompileも実行も通りますが、それは境界を捨てた状態です。
内部packageを**公開していないこと**まで検査します。

## 手で動かす場合

```sh
JQ_LAB_PORT=9999 ./run-runtime-lab.sh
```

親ディレクトリの `README.md` には、同じ流れを1コマンドずつ手で叩く手順があります。

生成物（`out/`）はコミットしません。
