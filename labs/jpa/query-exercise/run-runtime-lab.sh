#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
container="$run_id-jpa"
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
  for id in jpa-n-plus-one jpa-lazy-outside jpa-optimistic-lock; do
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

# ── 1. JPAの実装とJDBCドライバを取り出す ─────────────────────────────────
if ! mvn -q dependency:copy-dependencies -DoutputDirectory=out/lib \
    -DincludeScope=runtime >out/mvn.log 2>&1; then
  show 'Mavenの出力' out/mvn.log
  fail_all 'JPAの実装を取得できません（初回はダウンロードに時間がかかります）'
fi
classpath="out/classes:out/lib/*"

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
mkdir -p out/classes
cp -R src/main/resources/META-INF out/classes/META-INF
if ! javac -encoding UTF-8 --release 21 -cp "out/lib/*" -d out/classes \
    JpaHarness.java src/main/java/cafe/jpa/*.java >out/javac.log 2>&1; then
  show 'compileの失敗' out/javac.log
  fail_all 'Javaコードをcompileできません'
fi

if ! java -cp "$classpath" JpaHarness "$jdbc_url" postgres lab db/schema.sql \
    >out/harness.log 2>&1; then
  show '実行の出力' out/harness.log
  fail_all '実DBでの測定を完了できませんでした'
fi

printf '%s\n' '--- 実測（顧客5人・1人あたり注文3件）---'
grep '^RESULT	' out/harness.log | sed 's/^RESULT	/  /'

# ── 検査1: 関連をたどるのに使ったSQLの本数 ────────────────────────────────
statements="$(value list-statements)"
list_size="$(value list-size)"
list_items="$(value list-items)"
if [ -z "$statements" ]; then
  bad jpa-n-plus-one "一覧を取得できませんでした（$(value list-error)）"
elif [ "$list_size" != 5 ] || [ "$list_items" != 3 ]; then
  bad jpa-n-plus-one "顧客${list_size}人（期待5）・1人目の注文${list_items}件（期待3）でした。取得する内容は変えないでください"
elif [ "$statements" -le 2 ]; then
  pass jpa-n-plus-one "顧客5人と注文をSQL${statements}本で読みました（1件ずつ引くと6本になります）"
else
  bad jpa-n-plus-one "SQLを${statements}本発行しています（2本以内にしてください）。顧客を1件ずつ触るたびに追加のSELECTが飛んでいます"
fi

# ── 検査2: 閉じたあとでも使える値が返るか ─────────────────────────────────
one_name="$(value one-name)"
one_items="$(value one-items)"
one_error="$(value one-error)"
if [ -n "$one_error" ]; then
  bad jpa-lazy-outside "例外になりました（${one_error}）。EntityManagerを閉じたあとでは遅延読み込みができません"
elif [ "$one_name" = customer-1 ] && [ "$one_items" = 3 ]; then
  pass jpa-lazy-outside '閉じたあとでも読める形で、名前と注文3件が返りました'
else
  bad jpa-lazy-outside "返った値が name=${one_name}（期待customer-1）items=${one_items}（期待3）でした"
fi

# ── 検査3: 同時更新に気づけるか ──────────────────────────────────────────
conflict="$(value conflict)"
conflict_budget="$(value conflict-budget)"
case "$conflict" in
  detected)
    pass jpa-optimistic-lock "同じ行への2つ目の更新がOptimisticLockExceptionになりました（残った値=${conflict_budget}）"
    ;;
  overwritten)
    bad jpa-optimistic-lock "2つ目の更新が黙って上書きしました（残った値=${conflict_budget}）。列があるだけでは守られません。同時更新の検出に使わせる宣言が必要です"
    ;;
  *)
    bad jpa-optimistic-lock "同時更新を確かめられませんでした（${conflict}）"
    ;;
esac

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' '関連をたどる前に、まとめて取ってきているか（発行本数で分かる）'
printf '%s\n' 'EntityManagerを閉じる前に、必要な値をそろえているか'
printf '%s\n' 'version列を「ただの整数」にしていないか'
exit "$fail"
