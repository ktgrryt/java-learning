# ビルド修復演習

Java実践・開発基盤編『テストとビルド』のレッスン「Mavenと成果物」が採点に使います。

「BUILD SUCCESS と出ているのに、実は何もテストしていない」ビルドを直す演習です。
編集するのは `pom.xml` だけで、`reference/pom.xml` が模範解答、`run-runtime-lab.sh` が固定の採点
スクリプトです。ソースとテストは変更しません。

## 手で動かす場合

```sh
mvn clean package dependency:list
```

## 直したかどうかの見分け方

| 見るところ | 直っていないとき | 直ったとき |
|---|---|---|
| surefireの結果 | `Tests run:` が出ない | 9件が走って全て成功する |
| `dependency:list` | `org.junit.jupiter:...:compile` | `org.junit.jupiter:...:test` |
| `jar tf target/*.jar` | `test-classes/` や `surefire-reports/` が入る | `cafe/lab/*.class` だけ |
| Mavenの警告 | `'build.plugins.plugin.version' ... is missing` | 警告なし |

`skipTests` はsurefireが読むプロパティです。`<properties>` に書くとプロジェクト全体でテストが
走らなくなり、`package` まで進んでも成果物だけができます。JARへ入る範囲は `maven-jar-plugin` の
`classesDirectory`（既定は `target/classes`）が決めます。

生成物（`target/` `out/`）はコミットしません。
