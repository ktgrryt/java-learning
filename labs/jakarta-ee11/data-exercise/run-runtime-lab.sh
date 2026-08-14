#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

fail_all() {
  for id in data-compiles data-crud data-find data-query data-optional; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

# 学習者のリポジトリをソースツリーへ入れてからbuildする
cp exercise/OrderRepository.java src/main/java/cafe/orders/OrderRepository.java

# ── 1. Jakarta Data 1.0 のAPIに対してcompileできるか ──────────────────
# 実装（Hibernateなど）は入れていない。ここで確かめるのは宣言が仕様どおりかどうかで、
# javacがannotationの位置・戻り値の型・型引数を検査する。
if mvn -q -B compile >out/mvn.log 2>&1; then
  pass data-compiles 'Jakarta Data 1.0 のAPIに対してcompileできました'
else
  show 'compileの失敗' out/mvn.log
  fail_all 'compileできません。annotationの位置と戻り値の型を確認してください'
fi

# 宣言の形は、生成されたclassではなくソースで確かめる。
# annotationはこの仕様そのものなので、名前を検査するのが妥当（一般Javaの字面ではない）。
src=src/main/java/cafe/orders/OrderRepository.java
# コメントを落としてから見る。落とさないと、ひな形の説明文に書いてある
# `Optional<Order>` のような字面で検査が満たされてしまう。
# sedの範囲指定（/\/\*/,/\*\//d）は、1行で閉じるコメントのときに次のコメントまで
# 削ってしまう（範囲の終わりは次の行から探されるため）。1文字ずつ見る形にする。
code="$(awk '
  BEGIN { inblock = 0 }
  {
    line = $0
    sub(/\/\/.*/, "", line)
    out = ""
    i = 1
    while (i <= length(line)) {
      pair = substr(line, i, 2)
      if (inblock) {
        if (pair == "*/") { inblock = 0; i += 2 } else { i++ }
      } else if (pair == "/*") {
        inblock = 1; i += 2
      } else {
        out = out substr(line, i, 1); i++
      }
    }
    printf "%s ", out
  }' "$src")"

has() { printf '%s' "$code" | grep -q "$1"; }

# ── 2. 基本操作をCrudRepositoryから受け取っているか ────────────────────
if has '@Repository' && has 'extends *CrudRepository *< *Order *, *Long *>'; then
  pass data-crud '@Repository を付け、CrudRepository<Order, Long> を継承しました'
else
  bad data-crud '@Repository を付け、CrudRepository<Order, Long> を継承してください（保存・削除・全件は自分で宣言しません）'
fi

# ── 3. @Find + @By + @OrderBy で取り出しているか ──────────────────────
if has '@Find' && has '@By *( *"status" *)' && has '@OrderBy'; then
  pass data-find '@Find・@By("status")・@OrderBy で取り出しを宣言しました'
else
  bad data-find '@Find のメソッドで、引数に @By("status") を付け、@OrderBy で並びを指定してください'
fi

# ── 4. @Query で件数を数えているか ────────────────────────────────────
if has '@Query' && has 'COUNT' && printf '%s' "$code" | grep -qE 'long +[A-Za-z_][A-Za-z0-9_]* *\('; then
  pass data-query '@Query（JDQL）で件数を数えるメソッドを宣言しました'
else
  bad data-query '@Query に COUNT を使うJDQLを書き、戻り値を long にしてください'
fi

# ── 5. 1件取得の戻り値がOptionalか ───────────────────────────────────
if has 'Optional *< *Order *>'; then
  pass data-optional '1件取得の戻り値を Optional<Order> にしました'
else
  bad data-optional '1件だけ取り出すメソッドの戻り値を Optional<Order> にしてください（見つからないことを型で表します）'
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'jakarta.data.repository の @Repository / CrudRepository / @Find / @By / @OrderBy / @Query'
printf '%s\n' 'Order の属性名: id / customerId / status / amount'
exit "$fail"
