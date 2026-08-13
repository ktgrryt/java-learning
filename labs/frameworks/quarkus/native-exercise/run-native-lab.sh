#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
run_id="${JQ_LAB_RUN_ID:?JQ_LAB_RUN_ID is required}"
base_url="http://127.0.0.1:$port"
container_name="$run_id-quarkus-native"
image_name="$run_id/quarkus-native:lab"
mkdir -p runtime/classes

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  docker image rm "$image_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

fail_after_build() {
  message="$1"
  printf 'JQ_CHECK\tFAIL\tquarkus-native-api\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-native-health\t%s\n' "$message"
  printf 'JQ_CHECK\tFAIL\tquarkus-native-stop\t%s\n' "$message"
}

started_at=$(date +%s)
if ! mvn -q package -DskipTests -Dnative \
    -Dquarkus.native.container-build=true \
    -Dquarkus.native.container-runtime=docker \
    -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 \
    -Dquarkus.native.builder-image.pull=never >runtime/native-build.log 2>&1; then
  tail -100 runtime/native-build.log 2>/dev/null || true
  printf 'JQ_CHECK\tFAIL\tquarkus-native-build\tNative executableをbuildできません\n'
  fail_after_build 'Native buildが失敗したためcontainerを起動していません'
  exit 1
fi
build_seconds=$(($(date +%s) - started_at))
if ! find target -maxdepth 1 -type f -name '*-runner' -perm -111 | grep -q .; then
  printf 'JQ_CHECK\tFAIL\tquarkus-native-build\t実行可能なtarget/*-runnerがありません\n'
  fail_after_build 'Native executableがないためcontainerを起動していません'
  exit 1
fi
printf 'JQ_CHECK\tPASS\tquarkus-native-build\tNative executableを%s秒でbuildしました\n' "$build_seconds"

if ! docker build --pull=false -f Dockerfile.native-micro -t "$image_name" . >runtime/docker-build.log 2>&1; then
  tail -100 runtime/docker-build.log 2>/dev/null || true
  fail_after_build 'Native runtime container imageをbuildできません'
  exit 1
fi
if ! javac --release 21 -d runtime/classes NativeProbe.java >runtime/probe-compile.log 2>&1; then
  cat runtime/probe-compile.log
  fail_after_build 'HTTP probeをコンパイルできません'
  exit 1
fi
docker run --name "$container_name" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  --cap-drop ALL --security-opt no-new-privileges -p "127.0.0.1:$port:8080" \
  "$image_name" >runtime/container.log 2>&1 &

ready=0
attempt=0
while [ "$attempt" -lt 30 ]; do
  if java -cp runtime/classes NativeProbe wait "$base_url" >/dev/null 2>&1; then ready=1; break; fi
  attempt=$((attempt + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  docker logs "$container_name" 2>&1 | tail -100 || true
  fail_after_build 'Native containerが動的ポートで応答しません'
  exit 1
fi

java -cp runtime/classes NativeProbe verify "$base_url"
probe_status=$?
docker rm -f "$container_name" >/dev/null 2>&1 || true
if java -cp runtime/classes NativeProbe stopped "$base_url" >/dev/null 2>&1; then
  printf 'JQ_CHECK\tPASS\tquarkus-native-stop\t検証後にNative containerとHTTP listenerを停止しました\n'
  stop_status=0
else
  printf 'JQ_CHECK\tFAIL\tquarkus-native-stop\t検証後もNative HTTP listenerが応答しています\n'
  stop_status=1
fi
if [ "$probe_status" -ne 0 ] || [ "$stop_status" -ne 0 ]; then exit 1; fi
