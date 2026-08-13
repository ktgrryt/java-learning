#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
image="$run_id-image"
container="$run_id-container"
mkdir -p runtime/classes

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker image rm -f "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

fail_all() {
  printf 'JQ_CHECK\tFAIL\tcontainer-build\tcontainer imageをbuildできません\n'
  printf 'JQ_CHECK\tFAIL\tcontainer-nonroot\tnon-rootユーザーを確認できません\n'
  printf 'JQ_CHECK\tFAIL\tcontainer-readonly\tread-only filesystemで起動できません\n'
  printf 'JQ_CHECK\tFAIL\tcontainer-readiness\tcontainerのreadinessへ接続できません\n'
  exit 1
}

javac --release 21 -d runtime/classes HealthProbe.java || fail_all
docker build --network=none -f exercise/Dockerfile -t "$image" . || fail_all
printf 'JQ_CHECK\tPASS\tcontainer-build\t編集したDockerfileからimageをbuildしました\n'

fail=0
user="$(docker image inspect --format '{{.Config.User}}' "$image")"
case "$user" in ''|0|root)
  printf 'JQ_CHECK\tFAIL\tcontainer-nonroot\t最終imageがrootで実行されます。USERを指定してください\n'; fail=1 ;;
*) printf 'JQ_CHECK\tPASS\tcontainer-nonroot\t最終imageはnon-rootのUSER %sで実行されます\n' "$user" ;;
esac

if docker run --rm -d --name "$container" --read-only --tmpfs /tmp \
    --memory=256m --cpus=1 -p "127.0.0.1:$port:8080" "$image" >/dev/null; then
  printf 'JQ_CHECK\tPASS\tcontainer-readonly\tread-only・memory・CPU制限付きでcontainerを起動しました\n'
else
  printf 'JQ_CHECK\tFAIL\tcontainer-readonly\t制限付きcontainerを起動できません\n'; fail=1
fi

ready=0
i=0
while [ "$i" -lt 20 ]; do
  if java -cp runtime/classes HealthProbe "http://localhost:$port/health/ready" \
      >runtime/health.txt 2>/dev/null; then ready=1; break; fi
  i=$((i + 1)); sleep 1
done
if [ "$ready" -eq 1 ] && grep -Fq '200 READY' runtime/health.txt; then
  printf 'JQ_CHECK\tPASS\tcontainer-readiness\t実containerのreadinessがHTTP 200 READYを返しました\n'
else
  docker logs "$container" 2>/dev/null || true
  printf 'JQ_CHECK\tFAIL\tcontainer-readiness\treadinessがHTTP 200 READYになりません\n'; fail=1
fi
exit "$fail"
