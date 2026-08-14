#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
container="$run_id-tx"
image='postgres:16-alpine'
jdbc_url="jdbc:postgresql://127.0.0.1:$port/postgres"

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

# 学習者のファイル内容がそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
show() {
  [ -s "$2" ] || return 0
  printf '%s\n' "--- $1 ---"
  sed 's/^JQ_CHECK/JQ-CHECK/' "$2"
}

fail_all() {
  for id in tx-rollback tx-lost-update tx-crossing tx-idempotent; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

value() {
  sed -n "s/^RESULT	$1	//p" out/harness.log | head -1
}

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
  *) fail_all 'DockerまたはPodmanへ接続できません' ;;
esac

cleanup() { "$runtime" rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

# ── 1. JDBCドライバを取り出す（buildはMavenに任せない）─────────────────────
if ! mvn -q dependency:copy-dependencies -DoutputDirectory=out/lib \
    -DincludeScope=runtime >out/mvn.log 2>&1; then
  show 'Mavenの出力' out/mvn.log
  fail_all 'JDBCドライバを取得できません（初回はダウンロードに時間がかかります）'
fi
driver="$(ls out/lib/postgresql-*.jar 2>/dev/null | head -1)"
if [ -z "$driver" ]; then
  show 'Mavenの出力' out/mvn.log
  fail_all 'JDBCドライバのjarが見つかりません'
fi

# ── 2. 実PostgreSQLを起動する ────────────────────────────────────────
"$runtime" run --rm -d --name "$container" -e POSTGRES_PASSWORD=lab \
  -p "127.0.0.1:$port:5432" "$image" >out/container.log 2>&1 \
  || { show 'containerの出力' out/container.log; fail_all 'PostgreSQLを起動できません'; }

ready=0
waited=0
while [ "$waited" -lt 40 ]; do
  if "$runtime" exec "$container" pg_isready -U postgres >/dev/null 2>&1; then ready=1; break; fi
  waited=$((waited + 1))
  sleep 1
done
[ "$ready" -eq 1 ] || fail_all 'PostgreSQLが受付を始めません'

# ── 3. compileして流す ───────────────────────────────────────────────
if ! javac -encoding UTF-8 --release 21 -cp "$driver" -d out/classes \
    TxHarness.java exercise/TransferService.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'TransferService.javaをcompileできません'
fi

if ! java -cp "out/classes:$driver" TxHarness \
    "$jdbc_url" postgres lab db/schema.sql >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all '実DBでの測定を完了できませんでした'
fi

printf '%s\n' '--- 実測（実PostgreSQLを読んだ結果）---'
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

start_balance=100000

# ── 検査1: 失敗した送金が何も残さないか ───────────────────────────────────
rollback_outcome="$(value rollback-outcome)"
rollback_balance="$(value rollback-balance-a)"
rollback_transfers="$(value rollback-transfers)"
if [ "$rollback_outcome" = threw ] && [ "$rollback_balance" = "$start_balance" ] \
    && [ "$rollback_transfers" = 0 ]; then
  pass tx-rollback '存在しない口座への送金が失敗し、残高も記録も変わりませんでした'
elif [ "$rollback_balance" != "$start_balance" ]; then
  bad tx-rollback "送金元の残高が${start_balance}から${rollback_balance}へ減ったまま残りました（記録${rollback_transfers}件）。1つのトランザクションにまとめ、失敗したらrollbackしてください"
elif [ "$rollback_outcome" != threw ]; then
  bad tx-rollback "存在しない口座なのに成功して戻りました。UPDATEは0行更新でもエラーになりません。更新できた行数を確かめてください"
else
  bad tx-rollback "失敗したのに記録が${rollback_transfers}件残りました。1つのトランザクションにまとめてください"
fi

# ── 検査2: 同じ向きの同時送金で更新が失われないか ─────────────────────────
lost_a="$(value lost-balance-a)"
lost_b="$(value lost-balance-b)"
lost_transfers="$(value lost-transfers)"
want_a="$(value lost-expected-a)"
want_b="$(value lost-expected-b)"
want_transfers="$(value lost-expected-transfers)"
lost_failures="$(value lost-failures)"
if [ "$lost_a" = "$want_a" ] && [ "$lost_b" = "$want_b" ] \
    && [ "$lost_transfers" = "$want_transfers" ] && [ "$lost_failures" = 0 ]; then
  pass tx-lost-update "2スレッドで${want_transfers}件を同時送金しても、1円も失われませんでした（A=${lost_a} B=${lost_b}）"
elif [ "$lost_failures" != 0 ]; then
  bad tx-lost-update "${lost_failures}件が失敗しました（$(value lost-first-error)）"
else
  bad tx-lost-update "A=${lost_a}（期待${want_a}）B=${lost_b}（期待${want_b}）記録${lost_transfers}件（期待${want_transfers}）。読んで計算して書き戻すと、その間の送金を上書きします"
fi

# ── 検査3: 逆向きの同時送金が、待ち合わせで失敗しないか ─────────────────────
# 残高が正しいかは検査2で測る（同じ計算の書き方が効くので、ここで重ねて測らない）。
# ここで見るのは「逆向きが同時に来ても、1件も失敗せず全件記録できるか」だけにする。
# 残高の一致まで条件に入れると、たまたま競合しなかった実行で結果が変わってしまう。
crossing_a="$(value crossing-balance-a)"
crossing_b="$(value crossing-balance-b)"
crossing_transfers="$(value crossing-transfers)"
crossing_failures="$(value crossing-failures)"
want_crossing="$(value crossing-expected-transfers)"
if [ "$crossing_failures" = 0 ] && [ "$crossing_transfers" = "$want_crossing" ]; then
  pass tx-crossing "A→BとB→Aを同時に${want_crossing}件流しても、1件も失敗しませんでした（A=${crossing_a} B=${crossing_b}）"
elif [ "$crossing_failures" != 0 ]; then
  bad tx-crossing "${crossing_failures}件が失敗しました（$(value crossing-first-error)）。更新の順序をそろえるか、衝突（SQLState 40001 / 40P01）を捕まえて再試行してください"
else
  bad tx-crossing "記録が${crossing_transfers}件でした（期待${want_crossing}）。逆向きの送金も全件記録される必要があります"
fi

# ── 検査4: 同じ送金IDの2回目が反映されないか ─────────────────────────────
idem_first="$(value idempotent-first)"
idem_second="$(value idempotent-second)"
idem_a="$(value idempotent-balance-a)"
idem_b="$(value idempotent-balance-b)"
idem_transfers="$(value idempotent-transfers)"
want_idem_a="$(value idempotent-expected-a)"
want_idem_b="$(value idempotent-expected-b)"
if [ "$idem_first" = ok ] && [ "$idem_second" = ok ] && [ "$idem_a" = "$want_idem_a" ] \
    && [ "$idem_b" = "$want_idem_b" ] && [ "$idem_transfers" = 1 ]; then
  pass tx-idempotent '同じ送金IDの2回目は、例外も出さずに何も動かしませんでした'
elif [ "$idem_second" != ok ]; then
  bad tx-idempotent "2回目が失敗しました（${idem_second}）。送金IDの衝突は「すでに済んでいる」と読み替えて、何もせずに戻ってください"
else
  bad tx-idempotent "A=${idem_a}（期待${want_idem_a}）B=${idem_b}（期待${want_idem_b}）記録${idem_transfers}件（期待1）。残高を動かしてから記録すると、2回目に残高だけ二重に動きます"
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' '1件の送金が1つのトランザクションになっているか（autocommitのままだと1文ずつ確定する）'
printf '%s\n' '残高の計算をJavaでしているか、DBの UPDATE ... SET balance = balance + ? に任せているか'
printf '%s\n' 'UPDATEの戻り値（更新できた行数）を確かめているか'
printf '%s\n' '送金の記録を残高より先に入れているか'
exit "$fail"
