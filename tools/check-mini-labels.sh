#!/usr/bin/env bash
#
# mini実装（MiniWeb・MiniJdbc・MiniDi・MiniJpa・MiniValidator・MiniJUnit・MiniLogger）を使う章に
# 「### ⚠️ ここが本物と違う」が書かれているかを検査する。
#
#   ./tools/check-mini-labels.sh          … 検査する
#   ./tools/check-mini-labels.sh --list   … 章ごとの状況を一覧する
#
# 教材は Servlet・CDI・JPA・Validation・JUnit・JDBC を**学習用に書き直した最小実装**で練習させる。
# 書き方は本物と同じにしてあるが、挙動は削っている。どこまでが同じでどこからが違うのかを章ごとに
# 書いていないと、学習者は「教材でできたこと」を実務でできることだと思い込む（レビュー08-14の §7-7）。
#
# ラベルには3つを書く。検査もこの3つを見る。
#
#   1. 違いの箇条書き（`- ` の行が2つ以上）
#   2. 「書き方は本物と同じ」に当たる断り
#   3. `💡` 本物でだけ確かめられることと、実物を触れる場所
#
# **中身が事実と合っているかは機械では見られない。** ラベルを書くときは `content/lib/` の実装を
# 読むこと（§7-7の作業では、そうしたおかげで「MiniJUnitでは@BeforeEachが動かせない」という
# 事実と違う問題文が見つかった。実際には対応している）。
#
# サーバーもJDKも使わないので1秒で終わる。mini実装を足したり解説を書き換えたら通すこと。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 -u tools/check_mini_labels.py "$@"
