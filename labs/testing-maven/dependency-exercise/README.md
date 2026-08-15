# 依存の版そろえ演習

Java実践・開発基盤編『チーム開発・ビルド・品質管理』のレッスン「Maven・Gradleと依存管理」が採点に使います。

「コンパイルは通るのに実行時に落ちる」を、実際の依存解決で起こして直す演習です。編集するのは
`pom.xml` だけで、`reference/pom.xml` が模範解答です。ソースとテストは変更しません。

## 何が起きているか

```text
自分のアプリ → jackson-databind 2.18.9 → jackson-core 2.18.9 を前提にしている
             → jackson-core 2.11.0（昔のコードのために直接足された版）
```

Mavenは同じartifactに1つの版だけを選びます。直接書いた宣言のほうが近いので `jackson-core 2.11.0`
が採用され、`jackson-databind 2.18.9` が必要とするクラスが無くなります。

```sh
mvn dependency:list       # 選ばれた版を確かめる
mvn test                  # NoClassDefFoundError: com.fasterxml.jackson.core.util.JacksonFeature
```

## 直したかどうかの見分け方

| 見るところ | 直っていないとき | 直ったとき |
|---|---|---|
| `mvn test` | `Errors: 2`（実行時に落ちる） | 2件とも成功 |
| `dependency:list` | core 2.11.0 / databind 2.18.9 | 3つとも同じ版 |
| そろえた先 | 2.11.0（古い方へ落とした） | 2.18.9以降 |

古い方へ落とせばエラーは消えますが、それは「動いた」だけです。この演習では、databindが前提と
する版へそろえます。版を1か所で決める方法（BOMのimport、またはpropertyでの共有）を使うと、
次に依存を足したときも同じ判断を繰り返さずに済みます。

生成物（`target/` `out/`）はコミットしません。
