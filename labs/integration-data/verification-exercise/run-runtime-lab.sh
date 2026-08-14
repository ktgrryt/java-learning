#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
container="${run_id}-verify-db"
image='postgres:16-alpine'

# 採点側が選んだcontainer runtimeを使う。手で動かすときは接続できる方を自分で探す。
runtime="${JQ_CONTAINER_RUNTIME:-}"
if [ -z "$runtime" ]; then
  if docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    runtime=docker
  elif podman info --format '{{.Host.OS}}' >/dev/null 2>&1; then
    runtime=podman
  fi
fi
case "$runtime" in
  docker|podman) ;;
  *) printf '%s\n' 'DockerまたはPodmanへ接続できません' >&2; exit 1 ;;
esac

cleanup() { "$runtime" rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

fail_all() {
  for id in db-ready strategy-outbox strategy-deferred observed-sequence; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

answer() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" exercise/strategy.properties \
    | head -1 | tr -d '\015' | sed 's/[[:space:]]*$//'
}

# 1回の psql 呼び出し = 1つの接続。別の接続から見えるかは、呼び出しを分けて確かめる。
sql() {
  "$runtime" exec -i "$container" psql -U postgres -v ON_ERROR_STOP=1 -q -t -A 2>&1
}

reset_db() {
  printf 'TRUNCATE order_outbox, orders, seat_hold RESTART IDENTITY CASCADE;\n' | sql >/dev/null 2>&1
}

# ── DBを起動してスキーマを当てる ──────────────────────────────────────
"$runtime" run --rm -d --name "$container" -e POSTGRES_PASSWORD=lab \
  -p "127.0.0.1:${port}:5432" "$image" >/dev/null 2>&1 || fail_all 'PostgreSQLを起動できません'

ready=0
i=0
while [ "$i" -lt 40 ]; do
  if "$runtime" exec "$container" pg_isready -U postgres >/dev/null 2>&1; then ready=1; break; fi
  i=$((i + 1))
  sleep 0.5
done
[ "$ready" -eq 1 ] || fail_all 'PostgreSQLが応答しません'

if sql < db/schema.sql >out_schema.log 2>&1; then
  pass db-ready 'PostgreSQLを起動し、検証用スキーマを適用しました'
else
  sed 's/^JQ_CHECK/JQ-CHECK/' out_schema.log
  fail_all 'スキーマを適用できません'
fi

# ── テスト1: 別の接続から見えるか ─────────────────────────────────────
# 書く側の接続で注文と送信箱を入れる。方式によってCOMMITするかROLLBACKするかが変わる。
# そのあと別の接続で数える。見えるべき件数は2件（注文1件＋送信箱1件）。
strategy_outbox="$(answer 'test\.outbox-visible-from-other-connection')"
case "$strategy_outbox" in
  rollback) closing='ROLLBACK;' ;;
  truncate) closing='COMMIT;' ;;
  *) closing='' ;;
esac

if [ -z "$closing" ]; then
  bad strategy-outbox 'test.outbox-visible-from-other-connection へ rollback か truncate を書いてください'
else
  reset_db
  printf 'BEGIN;\nINSERT INTO orders (email) VALUES (%s);\nINSERT INTO order_outbox (order_id) SELECT order_id FROM orders WHERE email = %s;\n%s\n' \
    "'first@example.com'" "'first@example.com'" "$closing" | sql >out_write.log 2>&1
  visible="$(printf 'SELECT (SELECT count(*) FROM orders) + (SELECT count(*) FROM order_outbox);\n' \
    | sql | tr -d '[:space:]')"
  if [ "$visible" = "2" ]; then
    pass strategy-outbox '別の接続から注文と送信箱の2件が見えました'
  else
    bad strategy-outbox "別の接続から見えた件数が ${visible} 件でした（2件必要）。書いた側がコミットしていないと、外からは何も見えません"
  fi
  reset_db
fi

# ── テスト2: 遅延した一意制約は途中の重複を許すか ──────────────────────
# 遅延させた制約はコミットの瞬間に検査される。途中の重複を確かめたいので、
# コミットしてしまうとその瞬間に違反で落ちる。
strategy_deferred="$(answer 'test\.deferred-unique-allows-intermediate')"
case "$strategy_deferred" in
  rollback) closing='ROLLBACK;' ;;
  truncate) closing='COMMIT;' ;;
  *) closing='' ;;
esac

if [ -z "$closing" ]; then
  bad strategy-deferred 'test.deferred-unique-allows-intermediate へ rollback か truncate を書いてください'
else
  reset_db
  printf 'BEGIN;\nINSERT INTO seat_hold VALUES (7, %s);\nINSERT INTO seat_hold VALUES (7, %s);\nSELECT count(*) FROM seat_hold WHERE seat_no = 7;\n%s\n' \
    "'alice'" "'bob'" "$closing" > /tmp/deferred.sql
  if sql < /tmp/deferred.sql > out_deferred.log 2>&1 && grep -q '^2$' out_deferred.log; then
    pass strategy-deferred '遅延した一意制約のもとで、途中の重複が許されることを確かめました'
  else
    sed 's/^JQ_CHECK/JQ-CHECK/' out_deferred.log
    bad strategy-deferred '途中の重複を確かめられませんでした。遅延した制約はコミットの瞬間に検査されます'
  fi
  reset_db
fi

# ── 観察: ロールバックしても採番は戻るか ──────────────────────────────
reset_db
printf 'BEGIN;\nINSERT INTO orders (email) VALUES (%s);\nROLLBACK;\n' "'rolled@example.com'" | sql >/dev/null 2>&1
next_id="$(printf 'INSERT INTO orders (email) VALUES (%s) RETURNING order_id;\n' "'kept@example.com'" \
  | sql | tr -d '[:space:]')"
if [ "$next_id" = "1" ]; then
  measured=yes
else
  measured=no
fi
observed="$(answer 'observed\.sequence-after-rollback')"
if [ "$observed" = "$measured" ]; then
  pass observed-sequence "ロールバック後の採番の挙動を、実測と同じに記録しました（次のIDは ${next_id}）"
else
  bad observed-sequence 'observed.sequence-after-rollback を yes か no で記録してください。ロールバック後に実際どうなるかを、この結果から読み取ります'
fi
reset_db

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' 'psqlの呼び出し1回が接続1つ。別の接続から見えるかは、呼び出しを分けて確かめている'
printf '%s\n' 'db/schema.sql の uq_seat_hold に付いている DEFERRABLE INITIALLY DEFERRED'
exit "$fail"
