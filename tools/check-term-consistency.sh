#!/usr/bin/env bash
#
# 散文に裸の英語（container、image、build…）が残っていないかを検査する。
#
#   ./tools/check-term-consistency.sh            … 検査する
#   ./tools/check-term-consistency.sh --list     … 残っている箇所を全部出す
#   ./tools/check-term-consistency.sh --measure  … 語ごとの混在率を出す（失敗にしない）
#
# 同じものを英語とカタカナで呼び分けると、初学者は「container」と「コンテナ」が同じものだと
# 確信できず、別概念かどうかを毎回考えることになる。2026-08-15の実測では `container` 41% /
# `image` 39% / `timeout` 49% が混在していた（レビュー08-14の §7-4）。
#
# 見るのは `explanation` `task` `hints` `title` `message` だけ。**コードブロックと `…` の中は
# 見ない**（`solution` `starterCode` `samples` も対象外）。そこは英語が正しい。
#
#   一般名詞なら       → カタカナにする（container → コンテナ）
#   識別子・製品名なら → `…` で囲む（囲めば対象から外れる）
#   新しい仕様用語なら → tools/check_term_consistency.py の KEEP へ理由つきで足す
#
# 基準は docs/guide.md「英語のままにする語と、日本語にする語」にある。
# サーバーもJDKも使わないので1秒で終わる。解説や問題文を書いたら通すこと。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 -u tools/check_term_consistency.py "$@"
