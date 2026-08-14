#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
container="$run_id-jdbc"
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
  for id in jdbc-crud jdbc-injection jdbc-no-leak jdbc-rollback; do
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
# 接続数の上限を低くしておく。閉じ忘れは「いま何本開いているか」では測れない
# （参照が切れた接続はGCで回収され、数える時点には消えていることがある）。
# 枠を使い切って too many clients になる形＝本番で表に出る形で測る。
"$runtime" run --rm -d --name "$container" -e POSTGRES_PASSWORD=lab \
  -p "127.0.0.1:$port:5432" "$image" -c max_connections=10 >out/container.log 2>&1 \
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
    JdbcHarness.java exercise/CustomerDao.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'CustomerDao.javaをcompileできません'
fi

if ! java -cp "out/classes:$driver" JdbcHarness \
    "$jdbc_url" postgres lab db/schema.sql >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all '実DBでの測定を完了できませんでした'
fi

printf '%s\n' '--- 実測（実PostgreSQLを読んだ結果）---'
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

# ── 検査1: 登録して読み戻せるか ───────────────────────────────────────
crud_found="$(value crud-found)"
crud_missing="$(value crud-missing)"
crud_rows="$(value crud-rows)"
if [ "$crud_found" = Ada ] && [ "$crud_missing" = null ] && [ "$crud_rows" = 1 ]; then
  pass jdbc-crud '登録した1件を読み戻し、無いemailではnullを返しました'
else
  bad jdbc-crud "読み戻し=${crud_found}（期待Ada）無いemail=${crud_missing}（期待null）行数=${crud_rows}（期待1）$(value crud-error)"
fi

# ── 検査2: 値の中のSQLが実行されてしまわないか ─────────────────────────────
table_exists="$(value injection-table-exists)"
stored="$(value injection-stored)"
if [ "$table_exists" = true ] && [ "$stored" = "x'); DROP TABLE customer; --" ]; then
  pass jdbc-injection '表示名に混ぜたSQLが、ただの文字列として保存されました（表は消えていません）'
elif [ "$table_exists" != true ]; then
  bad jdbc-injection '表示名に混ぜた DROP TABLE が実行され、customer表が消えました。値を文字列に埋め込まず、プレースホルダで渡してください'
else
  bad jdbc-injection "保存された表示名が渡した値と違います（${stored}）。値はそのまま保存されるべきです"
fi

# ── 検査3: 接続を閉じているか（枠を使い切らないか）──────────────────────────
leak_calls="$(value leak-calls)"
leak_failures="$(value leak-failures)"
if [ "$leak_failures" = 0 ]; then
  pass jdbc-no-leak "${leak_calls}回呼んでも接続の枠（max_connections=10）を使い切りませんでした"
else
  bad jdbc-no-leak "${leak_calls}回のうち${leak_failures}回が接続できませんでした（$(value leak-first-error)）。閉じ忘れた接続が枠を使い切っています。Connection・Statement・ResultSetをtry-with-resourcesで閉じてください"
fi

# ── 検査4: 途中で失敗したまとめ登録が1件も残さないか ──────────────────────
rollback_outcome="$(value rollback-outcome)"
rollback_rows="$(value rollback-rows)"
if [ "$rollback_outcome" = threw ] && [ "$rollback_rows" = 0 ]; then
  pass jdbc-rollback '主キー違反でまとめ登録が失敗し、1件も残りませんでした'
elif [ "$rollback_outcome" != threw ]; then
  bad jdbc-rollback "主キー違反なのに成功して戻りました（残り${rollback_rows}件）"
else
  bad jdbc-rollback "失敗したのに${rollback_rows}件が残りました。まとめて1つのトランザクションにして、失敗したらrollbackしてください"
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' '値を + でSQLへ繋いでいないか（プレースホルダなら値の中身は解釈されない）'
printf '%s\n' 'Connection・Statement・ResultSet を try-with-resources で閉じているか'
printf '%s\n' 'insertAll が1件ずつ確定していないか（autocommitのままだと半分残る）'
exit "$fail"
