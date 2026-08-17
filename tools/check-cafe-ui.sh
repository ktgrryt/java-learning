#!/usr/bin/env bash
#
# カフェ画面をブラウザで実際に操作して確かめる。
#
#   ./tools/check-cafe-ui.sh              … 検査する（30秒ほど）
#   ./tools/check-cafe-ui.sh --keep-open  … 終わってもサーバとChromeを残す（手で見たいとき）
#
# 画面の検査はこれ1本しかない。`check-cafe-scene.sh` は店構えSVGの単体テストで、DOMも購入も
# 通らないため、**カフェ画面の描画・購入・通知・自動売上を守るものが他に無い**。
# サーバー側は `simulate-cafe.sh` と `check-achievements.sh` が数を見るが、
# 「買えるボタンが出て、押すと残高が価格ぶん減り、通知が出る」はここでしか分からない。
#
# 追加パッケージは要らない（node 24 組み込みの WebSocket で Chrome DevTools Protocol を叩く）。
# **Chromeが無い環境では省略して成功扱いにする** ― runtime-labが環境不足を省略するのと同じ扱いで、
# 手元にChromeが無い人のコミットを止めないため。
#
# 進捗ファイルは書き換えない（一時ディレクトリに用意した進捗で動かす）。
# その進捗には、買える設備と自動営業設備を持たせてある（残高0では購入経路を通れない）。
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
  echo "注意: Chrome / Chromium が見つからないため、カフェ画面の検査を省略します。"
  echo "      （画面の確認だけは機械化できていないので、変更したら手で開いて確かめてください）"
  exit 0
fi

if ! command -v node >/dev/null 2>&1; then
  echo "注意: node が見つからないため、カフェ画面の検査を省略します（node 20以降が必要）。"
  exit 0
fi

# shellcheck source=build.sh
source tools/build.sh
jq_build

ROOT="$(pwd)"
WORK="$(mktemp -d /tmp/jq-cafe-ui.XXXXXX)"
APP_PORT="${JQ_UI_PORT:-8351}"
CDP_PORT="${JQ_UI_CDP_PORT:-9351}"

cleanup() {
  if [[ "$KEEP_OPEN" == "1" ]]; then
    echo ""
    echo "残してあります: http://localhost:${APP_PORT}/#cafe （進捗は ${WORK}/progress.json）"
    echo "止めるときは: kill ${APP_PID:-} ${CHROME_PID:-}"
    return
  fi
  [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null || true
  [[ -n "${CHROME_PID:-}" ]] && kill "$CHROME_PID" 2>/dev/null || true
  # Chromeがプロファイルを掴んだまま消すと消し残るので、終わるのを待ってから片付ける
  wait "$APP_PID" 2>/dev/null || true
  wait "$CHROME_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# 教材と画面は本物を参照し、進捗だけ一時ディレクトリへ置く
ln -s "$ROOT/content" "$WORK/content"
ln -s "$ROOT/web" "$WORK/web"
ln -s "$ROOT/labs" "$WORK/labs"

# 買える設備と稼働中の自動営業設備を持つ進捗を作る。★は実在する問題キーから採る
python3 - "$WORK" <<'PY'
import glob, json, sys

work = sys.argv[1]
keys = []
for path in sorted(glob.glob('content/ch0[1-9]*.json')):
    chapter = json.load(open(path, encoding='utf-8'))
    for lesson in chapter.get('lessons') or []:
        tasks = ([lesson] if lesson.get('task') is not None else []) \
            + list(lesson.get('extraTasks') or [])
        for index, _ in enumerate(tasks, start=1):
            keys.append(f"{lesson['id']}#{index}")
keys = keys[:40]
cleared = {k: {'clearedAt': '2026-08-10', 'hintsUsed': 0, 'attempts': 1} for k in keys}
progress = {
    'onboardingCompleted': True,
    'cleared': cleared,
    'clearDates': ['2026-08-10'],
    'cafe': {
        'economyVersion': 24,
        'cash': 900_000_000,          # Rank5あたりまで買える額
        'cups': 4_000,
        'lifetimeCash': 5_000_000_000,
        'luckyCoinUnlockSeed': 1234567890123456789,
        'taskRewardCount': len(keys),
        'storeCount': 3,
        'ownedUpgrades': ['espresso', 'signboard'],
        'ownedAutomation': ['warming_pot'],   # 自動売上のtickを回すため
    },
}
json.dump(progress, open(f'{work}/progress.json', 'w'), ensure_ascii=False)
PY

# exec で java に置き換える。付けないと $! はサブシェルのPIDになり、kill しても
# java が生き残ってポートを掴み続ける（以降の実行が古いビルドを検査してしまう）。
# 先客がいると --exact-port で起動に失敗し、そのまま「古いサーバ」を検査してしまう。
# 黙って別のビルドを検査するのが最悪なので、ここで止める。
if curl -fsS -o /dev/null "http://localhost:${APP_PORT}/api/state" 2>/dev/null; then
  echo "ポート ${APP_PORT} で既に何かが応答しています。" >&2
  echo "  古い検査用サーバが残っている可能性があります: pkill -f 'jq.App --port ${APP_PORT}'" >&2
  echo "  別のポートで走らせるなら: JQ_UI_PORT=8352 ./tools/check-cafe-ui.sh" >&2
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
  echo "注意: Chromeへ接続できなかったため、カフェ画面の検査を省略します。"
  cat "$WORK/chrome.log" >&2
  exit 0
fi

node tools/check_cafe_ui.js "$APP_PORT" "$CDP_PORT"
