#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
runtime="${JQ_CONTAINER_RUNTIME:-}"
container="$run_id-sql"
image='postgres:16-alpine'

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

report_all_failed() {
  printf 'JQ_CHECK\tFAIL\tsql-primary-key\tPRIMARY KEYを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-foreign-key\tFOREIGN KEYを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-column-constraints\tNOT NULL・UNIQUE・CHECKを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-left-join\tLEFT JOINと集約結果を確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-having\tHAVINGによる集約後の絞り込みを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-index\t複合インデックスを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-explain\t実行計画を確認できません\n'
  exit 1
}

rejects() {
  printf '%s\n' "$1" | "$runtime" exec -i "$container" \
    psql -v ON_ERROR_STOP=1 -U postgres >/dev/null 2>&1
  [ "$?" -ne 0 ]
}

"$runtime" run --rm -d --name "$container" -e POSTGRES_PASSWORD=lab \
  -p "127.0.0.1:$port:5432" "$image" >/dev/null || report_all_failed

ready=0
i=0
while [ "$i" -lt 30 ]; do
  if "$runtime" exec "$container" pg_isready -U postgres >/dev/null 2>&1; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 1
done
[ "$ready" -eq 1 ] || report_all_failed

if ! "$runtime" exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres \
    <exercise/schema.sql >/dev/null; then
  report_all_failed
fi
if ! "$runtime" exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres \
    <seed.sql >/dev/null; then
  report_all_failed
fi

fail=0

if rejects "BEGIN; INSERT INTO customers(id,name,email) VALUES (1,'Again','again@example.test'); COMMIT;"; then
  printf 'JQ_CHECK\tPASS\tsql-primary-key\t実DBがcustomers.idの重複を拒否しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-primary-key\tcustomers.idへPRIMARY KEYが必要です\n'
  fail=1
fi

if rejects "BEGIN; INSERT INTO orders(id,customer_id,status,total,created_at) VALUES (201,999,'NEW',1,CURRENT_TIMESTAMP); COMMIT;"; then
  printf 'JQ_CHECK\tPASS\tsql-foreign-key\t実DBが存在しない顧客の注文を拒否しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-foreign-key\torders.customer_idへFOREIGN KEYが必要です\n'
  fail=1
fi

column_constraints=1
rejects "BEGIN; INSERT INTO customers(id,name,email) VALUES (4,NULL,'null-name@example.test'); COMMIT;" || column_constraints=0
rejects "BEGIN; INSERT INTO customers(id,name,email) VALUES (4,'Duplicate','aki@example.test'); COMMIT;" || column_constraints=0
rejects "BEGIN; INSERT INTO orders(id,customer_id,status,total,created_at) VALUES (202,1,'NEW',-1,CURRENT_TIMESTAMP); COMMIT;" || column_constraints=0
rejects "BEGIN; INSERT INTO orders(id,customer_id,status,total,created_at) VALUES (203,1,'DONE',1,CURRENT_TIMESTAMP); COMMIT;" || column_constraints=0
if [ "$column_constraints" -eq 1 ]; then
  printf 'JQ_CHECK\tPASS\tsql-column-constraints\tNOT NULL・UNIQUE・金額とstatusのCHECKが不正値を拒否しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-column-constraints\tNOT NULL・UNIQUE・2つのCHECKをすべて定義してください\n'
  fail=1
fi

expected_totals="$(printf 'Aki|1200.00\nMina|2500.00\nSora|0')"
if actual_totals="$("$runtime" exec -i "$container" psql -At -F '|' -v ON_ERROR_STOP=1 -U postgres \
    <exercise/paid_totals.sql 2>/dev/null)" && [ "$actual_totals" = "$expected_totals" ]; then
  printf 'JQ_CHECK\tPASS\tsql-left-join\tPAIDだけを集約し、注文のないSoraも0で残しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-left-join\tLEFT JOINのONでPAIDを絞り、Aki・Mina・Soraを集約してください\n'
  fail=1
fi

expected_high_value="$(printf 'Mina|2500.00\nAki|1200.00')"
if actual_high_value="$("$runtime" exec -i "$container" psql -At -F '|' -v ON_ERROR_STOP=1 -U postgres \
    <exercise/high_value_customers.sql 2>/dev/null)" && [ "$actual_high_value" = "$expected_high_value" ]; then
  printf 'JQ_CHECK\tPASS\tsql-having\t集約後に1000以上の顧客を絞り込みました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-having\tHAVINGでPAID合計1000以上を合計降順にしてください\n'
  fail=1
fi

if ! "$runtime" exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres \
    <performance-data.sql >/dev/null; then
  printf 'JQ_CHECK\tFAIL\tsql-index\t実行計画用データを準備できません\n'
  printf 'JQ_CHECK\tFAIL\tsql-explain\t実行計画用データを準備できません\n'
  exit 1
fi

explain_ok=1
if ! explain_output="$("$runtime" exec -i "$container" psql -At -v ON_ERROR_STOP=1 -U postgres \
    <exercise/explain.sql 2>/dev/null)"; then
  explain_ok=0
fi

index_columns="$(printf '%s' "SELECT string_agg(a.attname, ',' ORDER BY keys.ordinality) FROM pg_class i JOIN pg_index ix ON ix.indexrelid=i.oid CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS keys(attnum, ordinality) JOIN pg_attribute a ON a.attrelid=ix.indrelid AND a.attnum=keys.attnum WHERE i.relname='idx_orders_status_created_at' AND keys.ordinality <= ix.indnkeyatts;" | "$runtime" exec -i "$container" psql -At -U postgres 2>/dev/null | tail -n 1)"
if [ "$index_columns" = 'status,created_at' ]; then
  printf 'JQ_CHECK\tPASS\tsql-index\t(status, created_at)の複合インデックスを確認しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-index\tidx_orders_status_created_atを(status, created_at)で作成してください\n'
  fail=1
fi

if [ "$explain_ok" -eq 1 ] \
    && printf '%s' "$explain_output" | grep -q '"Index Name": "idx_orders_status_created_at"' \
    && printf '%s' "$explain_output" | grep -q '"Actual Rows"'; then
  printf 'JQ_CHECK\tPASS\tsql-explain\tEXPLAIN ANALYZEで実インデックス利用と実測行数を確認しました\n'
else
  printf 'JQ_CHECK\tFAIL\tsql-explain\tEXPLAIN (ANALYZE, FORMAT JSON)で対象queryの実行計画を出してください\n'
  fail=1
fi

exit "$fail"
