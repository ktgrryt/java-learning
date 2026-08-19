#!/usr/bin/env bash
#
# アプリの版が、書いてある全部の場所でそろっているかを検査する。
#
#   ./tools/check-version.sh          … 検査する
#   ./tools/check-version.sh --list   … 場所ごとの値を出す
#
# 版の置き場は `EnvironmentInfo.APP_VERSION` が正で、画面（設定パネル）と起動時の表示は
# そこを読む。ところが README の末尾にも配布物の目印として版を書いているので、**片方だけ
# 上げると利用者に見える2箇所が食い違う**。実際に v1.0.2 では README だけが先に進んでいた。
#
# 上げるときは手で書き換えず `./tools/bump-version.sh` を使う（両方を1回で書き換える）。
# この検査は、それを忘れて手で直したときに気づくためにある。1秒で終わる。
#
# 場所を増やしたら SOURCES に足すこと。足さない限り黙って対象から外れる。
set -euo pipefail

cd "$(dirname "$0")/.."

GREEN=$'\033[32m' RED=$'\033[31m' DIM=$'\033[2m' RESET=$'\033[0m'

# 「表示名 | ファイル | 版を取り出す sed」の3つ組。bump-version.sh もこの一覧に合わせて直す
SOURCES=(
  "APP_VERSION|src/main/java/jq/web/EnvironmentInfo.java|s/^ *public static final String APP_VERSION = \"\\(.*\\)\";$/\\1/p"
  "README|README.md|s/^version \\(.*\\)$/\\1/p"
)

FAILED=0
FIRST=""
for entry in "${SOURCES[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  file="${rest%%|*}"
  script="${rest#*|}"

  found="$(sed -n "$script" "$file")"
  count="$(printf '%s' "$found" | grep -c . || true)"

  if [[ "$count" -eq 0 ]]; then
    echo "${RED}NG${RESET}   $file に版の記述が見つかりません（${label} の書き方を変えたなら、この検査の取り出し方も直す）" >&2
    FAILED=1
    continue
  fi
  if [[ "$count" -gt 1 ]]; then
    echo "${RED}NG${RESET}   ${file} に版の記述が ${count} 箇所あります（1箇所にする）" >&2
    FAILED=1
    continue
  fi
  if [[ ! "$found" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "${RED}NG${RESET}   $file の版 \"$found\" が X.Y.Z の形ではありません" >&2
    FAILED=1
    continue
  fi

  if [[ "${1:-}" == "--list" ]]; then
    printf '  %-12s %-44s %s\n' "$label" "${DIM}$file${RESET}" "$found"
  fi

  if [[ -z "$FIRST" ]]; then
    FIRST="$found"
  elif [[ "$found" != "$FIRST" ]]; then
    echo "${RED}NG${RESET}   版が食い違っています: ${label} は ${found}、他は ${FIRST}（./tools/bump-version.sh で上げ直す）" >&2
    FAILED=1
  fi
done

if [[ "$FAILED" -ne 0 ]]; then
  exit 1
fi

echo "  ${GREEN}OK${RESET}   版は ${#SOURCES[@]}箇所すべて v${FIRST} でそろっています"
