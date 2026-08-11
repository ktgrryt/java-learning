#!/usr/bin/env bash
set -euo pipefail

LAB_DIR="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-app}"
WORK_DIR=""

cleanup() {
  if [[ -n "$WORK_DIR" ]]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

case "$VARIANT" in
  app)
    APP_DIR="$LAB_DIR/app"
    ;;
  reference)
    WORK_DIR="$(mktemp -d)"
    cp -R "$LAB_DIR/app/." "$WORK_DIR/"
    cp -R "$LAB_DIR/reference/src/." "$WORK_DIR/src/"
    cp "$LAB_DIR/reference/db/migration/V2__add_order_cancellation.sql" \
       "$WORK_DIR/db/migration/V2__add_order_cancellation.sql"
    cp "$LAB_DIR/reference/PR.md" "$WORK_DIR/PR.md"
    APP_DIR="$WORK_DIR"
    ;;
  *)
    echo "使い方: ./run-tests.sh [app|reference]" >&2
    exit 2
    ;;
esac

BUILD_DIR="$(mktemp -d)"
if [[ -z "$WORK_DIR" ]]; then
  WORK_DIR="$BUILD_DIR"
else
  EXTRA_BUILD_DIR="$BUILD_DIR"
  trap 'rm -rf "$WORK_DIR" "$EXTRA_BUILD_DIR"' EXIT
fi

find "$APP_DIR/src/main/java" "$APP_DIR/src/test/java" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -d "$BUILD_DIR"

java -Dlab.root="$APP_DIR" -cp "$BUILD_DIR" cafe.orders.OrderApplicationTest
