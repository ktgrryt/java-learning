# プラットフォーム独立の実測演習

Java実践・開発基盤編『Javaの成り立ちとプラットフォーム』のレッスン「Javaの設計目標」が採点に使います。

「同じclass fileがどこでも動く」ことと「どこでも同じ結果になる」ことは別だと確かめる演習です。
必要なのはJDKだけです。

編集するのは `exercise/OrderReport.java` で、`reference/` が模範解答です。`data/` の2ファイルは
UTF-8で保存されていて、変更しません。

## 採点で何をするか

**1回だけコンパイル**し、出来た同じclass fileを、環境の既定値だけ変えて4通り動かします。

| 実行条件 | 出発点で起きること |
|---|---|
| 既定 | 記録時刻がJVMの既定タイムゾーンで解釈される |
| `-Duser.language=tr -Duser.country=TR` | `"id".toUpperCase()` が `İD`、桁区切りが `1.234,50` |
| `-Duser.timezone=UTC` | このときだけ偶然正しくなる（**動く環境では気づけない**） |
| `-Dfile.encoding=ISO-8859-1` | UTF-8のファイルを既定の文字集合で復号して `cafÃ©` |

さらに別のデータ（`data/orders-alt.txt`）でも実行します。定数を出しているだけでは通りません。

期待する出力は `data/orders.txt` に対して次の4行です。

```
code=ID
total=1,234.50
recorded=2026-08-13
label=café
```

## 直し方の要点

環境の既定値に任せている4か所を、明示に変えます。

| 依存しているもの | 明示する |
|---|---|
| 既定の文字集合 | `new String(bytes, StandardCharsets.UTF_8)` |
| 既定のロケール（大文字化） | `toUpperCase(Locale.ROOT)` |
| 既定のロケール（数値の書式） | `String.format(Locale.ROOT, …)` |
| 既定のタイムゾーン | 記録時刻はUTCで扱う（`ZoneOffset.UTC`） |

bytecodeの移植性は、既定値の違いまで面倒を見てくれません。「開発機では動いたのに本番だけ結果が
違う」の多くはここから来ます。

## 手で動かす場合

```sh
JQ_LAB_PORT=9999 ./run-runtime-lab.sh
```

生成物（`out/`）はコミットしません。
