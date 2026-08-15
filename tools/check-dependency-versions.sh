#!/usr/bin/env bash
#
# labs が指定している版と、JDKのsecurity baselineを、公開情報と突き合わせる（レビュー08-14の §8.4）。
#
#   ./tools/check-dependency-versions.sh            … 突き合わせて表示する
#   ./tools/check-dependency-versions.sh --offline   … 宣言だけ一覧する（通信しない）
#
# 製品章（Spring Boot・Quarkus・Open Liberty・Jakarta EE・PostgreSQL）は教材の外で版が動くので、
# 放っておくと教材だけが古くなる。四半期ごと、またはJava CPU／製品minor更新のときに通す。
#
#   Mavenの依存    … Maven Central の maven-metadata.xml（権威ある一覧。検索APIは古いことがある）
#   JDKのbaseline  … Adoptium API の最新GA（Oracleのroadmapページは403で取得できない）
#
# 宣言した版が Central に**無い**ときだけ失敗する（ビルドが落ちるため）。
# 「新しい版が出ている」は警告にとどめる ― 上げるかは人が決めることで、
# **上げたら必ずそのlabをビルドして確かめる**必要がある（この道具は動くかどうかを見ていない）。
#
# ほかの検査と違い、これだけはネットワークを使う。通信できないときは一覧だけ出して失敗にしない。
set -euo pipefail

cd "$(dirname "$0")/.."

python3 -u tools/check_dependency_versions.py "$@"
