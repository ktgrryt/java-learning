# モジュール入門演習

Java基礎編『総仕上げ』のレッスン「import の総整理と、モジュールの概要」が採点に使います。

**`module-info.java` を自分で書く、基礎編で最初（で唯一）の演習です。** 必要なのはJDKだけで、
Maven・Docker・ネットワークは要りません（提出すると、アプリが `javac` で確かめます）。

書くのは**2行だけ**です。`cafe.core` が何を見せるか（`exports`）と、
`cafe.app` が何を使うか（`requires`）です。

| ファイル | 役割 |
|---|---|
| `src/cafe.core/module-info.java` | **編集する。** 見せるパッケージを宣言する（TODO ①） |
| `src/cafe.app/module-info.java` | **編集する。** 使うモジュールを宣言する（TODO ②） |
| `src/cafe.core/cafe/core/Greeter.java` | 参照専用。外へ見せるAPI |
| `src/cafe.core/cafe/core/internal/Style.java` | 参照専用。**public だが外へ出さない**内部実装 |
| `src/cafe.app/cafe/app/Main.java` | 参照専用。`Greeter` を使って2行出す |
| `probe-internal/` | 参照専用。**コンパイルできてはいけない**コード |
| `reference/` | 模範解答（画面には出ません） |

## 採点で何が起きるか

`./run-tests.sh` が3つを見ます。**1つは「コンパイルできないこと」**の確認です。

| 見るところ | 直っていないとき | 直ったとき |
|---|---|---|
| 2モジュールをまとめてコンパイル | `パッケージcafe.coreは表示不可です`（`requires` / `exports` が無い） | 通る |
| モジュールとして実行 | 実行できない | `☕ いらっしゃいませ、田中さん` などが出る |
| `probe-internal`（外から `cafe.core.internal`） | `internal` まで `exports` すると**使えてしまう** | 公開していないので通らない |

先頭に採点対象外の参考行を1つ出します ―― 同じソースを**クラスパス**で動かすと `module-info` を
読まずに動くこと（＝モジュールは必須ではないこと）の確認です。

## 手元で動かす

```bash
cd labs/basics/module-first-exercise
./run-tests.sh              # いまの src/ を検査する（最初は失敗します）
./run-tests.sh reference    # 模範解答を重ねて検査する（すべて通ります）
```

## この演習と `labs/modules/boundary-exercise` の違い

`labs/modules/boundary-exercise`（Java実践・開発基盤編『テストとビルド』）は、
**JARに入っているのに他のモジュールからは使えない**ところまで踏み込みます。
こちらは基礎編の入口なので、`exports` と `requires` を1行ずつ書いて
「公開を絞れる」ことだけを確かめます。
