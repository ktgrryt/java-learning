#!/usr/bin/env bash
#
# labs/ を実際に動かしたときの生成物を消す。
#
#   ./tools/clean-labs.sh          … 消す対象と大きさを一覧するだけ（何も消さない）
#   ./tools/clean-labs.sh --yes    … 実際に消す
#
# runtime-lab や外部labを動かすと、`target/` や展開したserver、native imageの中間物が
# 溜まる。`.gitignore` に入っているのでコミットはされないが、消える仕組みが無いため
# 放っておくと数GBになる（実測で labs/ 全体 2.4GB、うち jakarta-web が 1.9GB）。
#
# 消すのは **Gitが無視しているファイルだけ** である（`git clean -X`）。教材やlabの
# ソースは追跡対象なので消えない。判断を機械に任せず、既定では一覧だけ出して
# `--yes` を付けたときにしか消さない ― 消したものはビルドし直せるが、
# 「気づかないうちに消えた」は避けたい。
#
# 消したあとで labs を使う章へ進むときは、最初の「事前確認」レッスンから
# 必要なツールと版を確かめ直せる（生成物はビルドし直せば戻る）。
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -d labs ]]; then
  echo "labs/ がありません。プロジェクトのルートから実行してください。" >&2
  exit 1
fi

CONFIRM=0
for arg in "$@"; do
  case "$arg" in
    --yes) CONFIRM=1 ;;
    *) echo "知らない引数です: $arg（使えるのは --yes だけ）" >&2; exit 1 ;;
  esac
done

# git clean は「ディレクトリごと無視されている」場合にその中身を1件ずつ列挙しないので、
# 大きさは du で測る。-d は無視されたディレクトリ（target/ など）も対象に含める。
# macOSの既定のbashは3.2なので mapfile は使えない（他のtoolsも3.2で動く書き方にしてある）。
targets=()
while IFS= read -r line; do
  targets+=("$line")
done < <(git clean -Xdn labs | sed 's/^Would remove //')

if [[ ${#targets[@]} -eq 0 ]]; then
  echo "labs/ に消せる生成物はありません。"
  exit 0
fi

total="$(du -sh labs 2>/dev/null | awk '{print $1}')"
echo "labs/ の現在の大きさ: ${total}"
echo "消す対象（Gitが無視しているもの）: ${#targets[@]}件"
for t in "${targets[@]}"; do
  size="$(du -sh "$t" 2>/dev/null | awk '{print $1}')"
  printf '  %-8s %s\n' "${size:-?}" "$t"
done

if [[ "$CONFIRM" != "1" ]]; then
  echo ""
  echo "何も消していません。実際に消すには --yes を付けてください:"
  echo "    ./tools/clean-labs.sh --yes"
  exit 0
fi

git clean -Xdf labs >/dev/null
echo ""
echo "消しました。labs/ の大きさ: $(du -sh labs 2>/dev/null | awk '{print $1}')"
