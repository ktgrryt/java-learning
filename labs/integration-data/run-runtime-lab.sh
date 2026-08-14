#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
container="$run_id-db"
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

fail_all() {
  printf 'JQ_CHECK\tFAIL\tdb-migrations\tPostgreSQLへmigrationを適用できません\n'
  printf 'JQ_CHECK\tFAIL\tdb-backward-insert\t旧application形式のINSERTを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tdb-unique\temailの一意制約を確認できません\n'
  printf 'JQ_CHECK\tFAIL\tdb-outbox-index\toutboxのindexを確認できません\n'
  exit 1
}

"$runtime" run --rm -d --name "$container" -e POSTGRES_PASSWORD=lab \
  -p "127.0.0.1:$port:5432" "$image" >/dev/null || fail_all

ready=0
i=0
while [ "$i" -lt 30 ]; do
  if "$runtime" exec "$container" pg_isready -U postgres >/dev/null 2>&1; then ready=1; break; fi
  i=$((i + 1)); sleep 1
done
[ "$ready" -eq 1 ] || fail_all

apply_ok=1
for file in db/migration/V1__create_customer.sql exercise/V2__add_customer_status.sql db/migration/V3__create_outbox.sql; do
  if ! "$runtime" exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres <"$file" >/dev/null; then
    apply_ok=0; break
  fi
done

fail=0
if [ "$apply_ok" -eq 1 ]; then
  printf 'JQ_CHECK\tPASS\tdb-migrations\t空のPostgreSQLへV1からV3を順番に適用しました\n'
else
  printf 'JQ_CHECK\tFAIL\tdb-migrations\tmigrationが途中で失敗しました\n'; fail=1
fi

insert_sql="INSERT INTO customer(email, display_name, created_at) VALUES ('a@example.test','A',CURRENT_TIMESTAMP); SELECT status FROM customer WHERE email='a@example.test';"
status="$(printf '%s' "$insert_sql" | "$runtime" exec -i "$container" psql -At -v ON_ERROR_STOP=1 -U postgres 2>/dev/null | tail -n 1)"
if [ "$status" = ACTIVE ]; then
  printf 'JQ_CHECK\tPASS\tdb-backward-insert\tstatusを知らない旧INSERTへACTIVEの既定値が入りました\n'
else
  printf 'JQ_CHECK\tFAIL\tdb-backward-insert\tstatusにはDEFAULT ACTIVEが必要です\n'; fail=1
fi

if ! printf '%s' "INSERT INTO customer(email,display_name,created_at) VALUES ('a@example.test','B',CURRENT_TIMESTAMP);" \
    | "$runtime" exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres >/dev/null 2>&1; then
  printf 'JQ_CHECK\tPASS\tdb-unique\t実DBがemailの重複を一意制約で拒否しました\n'
else
  printf 'JQ_CHECK\tFAIL\tdb-unique\temailの重複が拒否されませんでした\n'; fail=1
fi

# index名はV3 migrationが作るものに合わせる（V3は編集対象ではない）。
index_count="$(printf '%s' "SELECT count(*) FROM pg_indexes WHERE indexname='ix_outbox_unpublished';" \
  | "$runtime" exec -i "$container" psql -At -U postgres | tail -n 1)"
if [ "$index_count" = 1 ]; then
  printf 'JQ_CHECK\tPASS\tdb-outbox-index\toutbox未送信検索用indexを実DBで確認しました\n'
else
  printf 'JQ_CHECK\tFAIL\tdb-outbox-index\toutbox未送信検索用indexがありません\n'; fail=1
fi
exit "$fail"
