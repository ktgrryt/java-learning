#!/usr/bin/env bash
#
# 学習画面をブラウザで実際に操作して確かめる。
#
#   ./tools/check-learn-ui.sh              … 検査する（40秒ほど）
#   ./tools/check-learn-ui.sh --keep-open  … 終わってもサーバとChromeを残す（手で見たいとき）
#
# このアプリの中心は「コードを書く → 試しに実行 → 提出して採点 → ★と報酬 → 次へ」で、そこに
# ヒント・模範解答・自動保存・カフェへの寄り道・復習がぶら下がっている。
# **この経路を通す検査はここだけ**である。
# `verify-solutions.sh` はサーバー側の採点しか見ないので、画面のJS（採点結果の描画、
# `applyDelta` の上書き、通知、復習の出題）が壊れても他の検査は全部通ってしまう。
# `check-cafe-ui.sh` はカフェ画面の担当で、学習画面はレッスンが開けることしか見ていない。
#
# 追加パッケージは要らない（node 24 組み込みの WebSocket で Chrome DevTools Protocol を叩く）。
# **Chromeが無い環境では省略して成功扱いにする** ― runtime-labが環境不足を省略するのと同じ扱いで、
# 手元にChromeが無い人のコミットを止めないため。
#
# 進捗ファイルは書き換えない（一時ディレクトリに用意した進捗で動かす）。
# その進捗は「初回案内は済み・★は0」にしてある ― ★を付ける瞬間（初回クリアの報酬と通知）が
# この検査でいちばん見たいところなので、あらかじめクリア済みにはしない。
set -euo pipefail

cd "$(dirname "$0")/.."

KEEP_OPEN=0
for arg in "$@"; do
  case "$arg" in
    --keep-open) KEEP_OPEN=1 ;;
    *) echo "知らない引数です: $arg" >&2; exit 1 ;;
  esac
done

CHROME=""
for candidate in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "/Applications/Chromium.app/Contents/MacOS/Chromium" \
    "$(command -v google-chrome || true)" \
    "$(command -v chromium || true)"; do
  if [[ -n "$candidate" && -x "$candidate" ]]; then CHROME="$candidate"; break; fi
done

if [[ -z "$CHROME" ]]; then
  echo "注意: Chrome / Chromium が見つからないため、学習画面の検査を省略します。"
  echo "      （画面の確認だけは機械化できていないので、変更したら手で開いて確かめてください）"
  exit 0
fi

if ! command -v node >/dev/null 2>&1; then
  echo "注意: node が見つからないため、学習画面の検査を省略します（node 20以降が必要）。"
  exit 0
fi

# shellcheck source=build.sh
source tools/build.sh
jq_build

ROOT="$(pwd)"
WORK="$(mktemp -d /tmp/jq-learn-ui.XXXXXX)"
APP_PORT="${JQ_LEARN_PORT:-8353}"
CDP_PORT="${JQ_LEARN_CDP_PORT:-9353}"
# 📣ひらめきメガホンを**すでに持っている人**の画面を見るための2台目。
# 進捗を1台で作り替えることはできない（サーバは停止時に書き戻すので、動かしながら
# ファイルを差し替えても元へ戻る）。1台目は止めず、別のポートと別の進捗で並べて立てる。
OWNED_PORT="$((APP_PORT + 1))"

cleanup() {
  if [[ "$KEEP_OPEN" == "1" ]]; then
    echo ""
    echo "残してあります: http://localhost:${APP_PORT}/#1-1 （進捗は ${WORK}/progress.json）"
    echo "📣を所持している側: http://localhost:${OWNED_PORT}/#review"
    echo "止めるときは: kill ${APP_PID:-} ${OWNED_PID:-} ${CHROME_PID:-}"
    return
  fi
  [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null || true
  [[ -n "${OWNED_PID:-}" ]] && kill "$OWNED_PID" 2>/dev/null || true
  [[ -n "${CHROME_PID:-}" ]] && kill "$CHROME_PID" 2>/dev/null || true
  # Chromeがプロファイルを掴んだまま消すと消し残るので、終わるのを待ってから片付ける
  wait "$APP_PID" 2>/dev/null || true
  wait "$OWNED_PID" 2>/dev/null || true
  wait "$CHROME_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# 教材と画面は本物を参照し、進捗だけ一時ディレクトリへ置く
ln -s "$ROOT/content" "$WORK/content"
ln -s "$ROOT/web" "$WORK/web"
ln -s "$ROOT/labs" "$WORK/labs"

# 初回案内は済み・★は0から始める。案内が出たままだと振り分けが `menu` に固定され
# （`web/app.js` の `routeFromHash`）、レッスンを開けない
#
# コインだけは最初から持たせる。カフェへ寄り道して帰る経路の検査で実際に1つ買うためで、
# 1問ぶんの報酬では最も安い設備（4,000コイン）に届かない。★は0のままなので、
# ★が付く瞬間（初回クリアの報酬と通知）はこれまでどおり見られる。
# `economyVersion` を2以上にしておくのは、1だと初版とみなされて残高が50倍に換算されるためである
# （`CafeEconomy` の読み込み）。
echo '{"onboardingCompleted":true,"cafe":{"cash":20000,"economyVersion":2}}' > "$WORK/progress.json"

# exec で java に置き換える。付けないと $! はサブシェルのPIDになり、kill しても
# java が生き残ってポートを掴み続ける（以降の実行が古いビルドを検査してしまう）。
# 先客がいると --exact-port で起動に失敗し、そのまま「古いサーバ」を検査してしまう。
# 黙って別のビルドを検査するのが最悪なので、ここで止める。
if curl -fsS -o /dev/null "http://localhost:${APP_PORT}/api/state" 2>/dev/null; then
  echo "ポート ${APP_PORT} で既に何かが応答しています。" >&2
  echo "  古い検査用サーバが残っている可能性があります: lsof -ti:${APP_PORT} | xargs kill" >&2
  echo "  別のポートで走らせるなら: JQ_LEARN_PORT=8354 ./tools/check-learn-ui.sh" >&2
  exit 1
fi

(cd "$WORK" && exec "$JQ_JAVA" -Dfile.encoding=UTF-8 -cp "$ROOT/build/classes" \
  jq.App --port "$APP_PORT" --exact-port > "$WORK/server.log" 2>&1) &
APP_PID=$!

for _ in $(seq 1 40); do
  curl -fsS -o /dev/null "http://localhost:${APP_PORT}/api/state" 2>/dev/null && break
  sleep 0.5
done
if ! curl -fsS -o /dev/null "http://localhost:${APP_PORT}/api/state" 2>/dev/null; then
  echo "サーバを起動できませんでした。${WORK}/server.log を見てください。" >&2
  cat "$WORK/server.log" >&2
  exit 1
fi

"$CHROME" --headless=new --remote-debugging-port="$CDP_PORT" \
  --user-data-dir="$WORK/chrome" --no-first-run --disable-gpu \
  about:blank > "$WORK/chrome.log" 2>&1 &
CHROME_PID=$!

for _ in $(seq 1 40); do
  curl -fsS -o /dev/null "http://127.0.0.1:${CDP_PORT}/json/version" 2>/dev/null && break
  sleep 0.5
done
if ! curl -fsS -o /dev/null "http://127.0.0.1:${CDP_PORT}/json/version" 2>/dev/null; then
  echo "注意: Chromeへ接続できなかったため、学習画面の検査を省略します。"
  cat "$WORK/chrome.log" >&2
  exit 0
fi

# ── 2台目（📣を所持している進捗）─────────────────────────────────────────
#
# 解放条件を取り終わった人に「連続 N / 12問」を出し続けないことを見る。所持を後から
# 与える経路はアプリに無い（買うには解放と250,000コインが要る）ので、最初から持っている
# 進捗を用意する。答えたクイズを1問だけ入れてあり、quizPlans が無いので期限切れ扱いになる。
mkdir -p "$WORK/owned"
ln -s "$ROOT/content" "$WORK/owned/content"
ln -s "$ROOT/web" "$WORK/owned/web"
ln -s "$ROOT/labs" "$WORK/owned/labs"
# クリア済みの問題を1問入れてある（期限切れになる古い日付）。これが無いと復習ホームが
# 「復習できる問題はまだありません」の分岐へ行き、案内（.review-quiz-note）自体が出ない
cat > "$WORK/owned/progress.json" <<'JSON'
{"onboardingCompleted":true,
 "cleared":{"1-1#1":{"clearedAt":"2026-07-01","hintsUsed":0,"attempts":1}},
 "attempts":{"1-1#1":1},"bestPassed":{"1-1#1":1},"clearDates":["2026-07-01"],
 "quizChoices":{"1-3#0":0},
 "cafe":{"economyVersion":2,"cash":0,"ownedItems":["quiz_crown"]}}
JSON

if curl -fsS -o /dev/null "http://localhost:${OWNED_PORT}/api/state" 2>/dev/null; then
  echo "ポート ${OWNED_PORT} で既に何かが応答しています（📣所持の検査に使います）。" >&2
  echo "  止めるなら: lsof -ti:${OWNED_PORT} | xargs kill" >&2
  exit 1
fi
(cd "$WORK/owned" && exec "$JQ_JAVA" -Dfile.encoding=UTF-8 -cp "$ROOT/build/classes" \
  jq.App --port "$OWNED_PORT" --exact-port > "$WORK/owned-server.log" 2>&1) &
OWNED_PID=$!
for _ in $(seq 1 40); do
  curl -fsS -o /dev/null "http://localhost:${OWNED_PORT}/api/state" 2>/dev/null && break
  sleep 0.5
done
if ! curl -fsS -o /dev/null "http://localhost:${OWNED_PORT}/api/state" 2>/dev/null; then
  echo "2台目のサーバを起動できませんでした。${WORK}/owned-server.log を見てください。" >&2
  cat "$WORK/owned-server.log" >&2
  exit 1
fi

node tools/check_learn_ui.js "$APP_PORT" "$CDP_PORT" "$OWNED_PORT"
