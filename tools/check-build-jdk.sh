#!/usr/bin/env bash
# build.shが警告に使うJDK security baseline判定を検査する。
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck source=build.sh
source tools/build.sh

for version in 21.0.12 21.0.12+7 25.0.4 26.0.2 27; do
  if ! jq_jdk_meets_security_baseline "$version"; then
    echo "受け付けるべきJDKを拒否しました: $version" >&2
    exit 1
  fi
done

for version in 21 21.0.5 21.0.11 22.0.2 23.0.2 24.0.2 25.0.3 26.0.1 invalid; do
  if jq_jdk_meets_security_baseline "$version"; then
    echo "古い、または対象外のJDKを受け付けました: $version" >&2
    exit 1
  fi
done

echo "build JDK security warning baseline: すべて合格"
