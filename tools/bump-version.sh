#!/usr/bin/env bash
#
# アプリの版を1回の実行で全部の場所へ書き込む。
#
#   ./tools/bump-version.sh patch            … 1.0.1 → 1.0.2
#   ./tools/bump-version.sh minor            … 1.0.1 → 1.1.0
#   ./tools/bump-version.sh major            … 1.0.1 → 2.0.0
#   ./tools/bump-version.sh 1.2.3            … 版を直に指定する
#   ./tools/bump-version.sh patch --commit   … 書き換えた版のファイルだけをコミットする
#
# 版は2箇所に見えている ― 画面と起動時の表示が読む `EnvironmentInfo.APP_VERSION` と、
# 配布物の目印である README の末尾。手で上げると片方を忘れる（v1.0.2 では README だけが
# 先に進んだ）。だからここで両方まとめて書き換え、最後に `check-version.sh` で確かめる。
#
# **いま入っている版は `APP_VERSION` から数える**（README ではなく）。画面に出ているのが
# そちらなので、食い違っていればこの実行でそろう。
#
# 書き換える場所は `check-version.sh` の SOURCES と対になっている。増やしたら両方に足す。
set -euo pipefail

cd "$(dirname "$0")/.."

DIM=$'\033[2m' BOLD=$'\033[1m' RESET=$'\033[0m'

JAVA_FILE="src/main/java/jq/web/EnvironmentInfo.java"
README_FILE="README.md"

usage() {
  sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'
}

LEVEL=""
COMMIT=0
for arg in "$@"; do
  case "$arg" in
    patch|minor|major) LEVEL="$arg" ;;
    --commit) COMMIT=1 ;;
    [0-9]*.[0-9]*.[0-9]*) LEVEL="$arg" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "使えない引数です: ${arg}" >&2; echo >&2; usage >&2; exit 1 ;;
  esac
done

if [[ -z "$LEVEL" ]]; then
  usage >&2
  exit 1
fi

CURRENT="$(sed -n 's/^ *public static final String APP_VERSION = "\(.*\)";$/\1/p' "$JAVA_FILE")"
if [[ ! "$CURRENT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "いまの版を ${JAVA_FILE} から読めませんでした（読めたのは \"${CURRENT}\"）" >&2
  exit 1
fi

IFS=. read -r MAJOR MINOR PATCH <<< "$CURRENT"
case "$LEVEL" in
  patch) NEXT="${MAJOR}.${MINOR}.$((PATCH + 1))" ;;
  minor) NEXT="${MAJOR}.$((MINOR + 1)).0" ;;
  major) NEXT="$((MAJOR + 1)).0.0" ;;
  *)     NEXT="$LEVEL" ;;
esac

if [[ ! "$NEXT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "版の形が X.Y.Z ではありません: ${NEXT}" >&2
  exit 1
fi

# 下げるのは事故のほうが多いので止める（意図してやるなら手で書き換える）
lower_or_same() {
  [[ "$(printf '%s\n%s\n' "$CURRENT" "$NEXT" | sort -t. -k1,1n -k2,2n -k3,3n | head -1)" == "$NEXT" ]]
}
if lower_or_same; then
  echo "いまの版 v${CURRENT} より新しくなりません: v${NEXT}" >&2
  exit 1
fi

# ---- 書き換える -------------------------------------------------------------
# sed -i は BSD と GNU で引数が違うので、一時ファイルへ書いて置き換える。
# 「書き換わらなかった」ことは失敗にしない ― 片方だけ先に上がっていることがあり、
# 見たいのは差分ではなく**書き終わったあとの値**である。
replace() {
  local file="$1" write="$2" read_back="$3" tmp got
  tmp="$(mktemp)"
  sed "$write" "$file" > "$tmp"
  got="$(sed -n "$read_back" "$tmp")"
  if [[ "$got" != "$NEXT" ]]; then
    rm -f "$tmp"
    echo "${file} に v${NEXT} を書けませんでした（読み返せたのは \"${got}\"）。" >&2
    echo "版の書き方を変えたなら、この置き換えと check-version.sh の両方を直すこと。" >&2
    return 1
  fi
  cat "$tmp" > "$file"   # mv だと権限とinodeが変わるので中身だけ差し替える
  rm -f "$tmp"
}

replace "$JAVA_FILE" \
  "s/^\( *public static final String APP_VERSION = \"\).*\(\";\)$/\\1${NEXT}\\2/" \
  's/^ *public static final String APP_VERSION = "\(.*\)";$/\1/p'
replace "$README_FILE" \
  "s/^version .*$/version ${NEXT}/" \
  's/^version \(.*\)$/\1/p'

echo
echo "  ${BOLD}v${CURRENT} → v${NEXT}${RESET}"
echo
./tools/check-version.sh --list
echo

# ---- コミット ---------------------------------------------------------------
if [[ "$COMMIT" -eq 1 ]]; then
  git commit -m "v${NEXT}" -- "$JAVA_FILE" "$README_FILE"
  echo
  echo "  ${DIM}push するには: git push${RESET}"
else
  echo "  ${DIM}commit するには: git commit -m \"v${NEXT}\" -- ${JAVA_FILE} ${README_FILE}${RESET}"
  echo "  ${DIM}（次からは --commit を付ければ、上げてコミットまで1回で終わる）${RESET}"
fi
