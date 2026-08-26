#!/usr/bin/env bash
#
# 速い検査をまとめて走らせる。
#
#   ./tools/check-all.sh          … 通信しない検査を全部走らせる（1〜2分）
#   ./tools/check-all.sh --net    … labsの版とJDK baselineの追随（通信する）も含める
#
# どれを走らせるかは `docs/guide.md`「どこまで検証するか」の表で決めるのが本筋だが、
# **コミット前にひとまず全部通す**ときに表を引くのは無駄なので、ここで束ねる。
# 本数は下の CHECKS がそのまま持つ（数を文章に書くと検査を足したときに必ず食い違う）。
#
# ここに入れるのは「速い」ものだけである。含めないもの:
#   ./tools/verify-solutions.sh          … 全件20〜30分。教材を触ったら別に走らせる
#   ./tools/check-dependency-versions.sh … 通信する（--net で入る）
#
# 1本でも落ちたら最後にまとめて知らせる（落ちた時点では止めない ― 何本落ちているかを
# 1回の実行で知りたいため）。画面の検査（カフェ・学習・エディタ）はChromeが無ければ
# 自分で省略する。
#
# 実行にかかる時間はこの3本が大きい（合わせて1分半ほど）。ブラウザを起こして実際に操作するので、
# 速さではなく「他の検査が見ていないもの＝画面のJS」を見るために入っている。
set -euo pipefail

cd "$(dirname "$0")/.."

CHECKS=(
  check-build-jdk.sh
  check-version.sh
  check-contrast.sh
  check-content-inventory.sh
  check-guide-numbers.sh
  check-objectives.sh
  check-objective-terms.sh
  check-chapter-refs.sh
  check-term-consistency.sh
  check-quiz-fairness.sh
  check-case-fairness.sh
  check-starter-imports.sh
  check-starter-syntax.sh
  check-hint-dependency.sh
  check-forward-terms.sh
  check-task-reference.sh
  check-constant-output.sh
  check-copyable-output.sh
  check-input-domain.sh
  check-explanation-output.sh
  check-block-alignment.sh
  check-markdown-lists.sh
  check-mini-labels.sh
  check-source-checks.sh
  check-source-alternatives.sh
  check-optional-task.sh
  check-layer-completion.sh
  check-onboarding.sh
  check-progress-load.sh
  check-learning-day.sh
  check-review-schedule.sh
  check-review-economy.sh
  check-achievements.sh
  check-artifact-validator.sh
  check-preflight-runner.sh
  check-project-runner.sh
  check-runtime-lab-runner.sh
  check-web-security.sh
  simulate-cafe.sh
  check-cafe-ui.sh
  check-learn-ui.sh
  check-editor-ui.sh
)

if [[ "${1:-}" == "--net" ]]; then
  CHECKS+=(check-dependency-versions.sh)
elif [[ $# -gt 0 ]]; then
  echo "知らない引数です: $1（使えるのは --net だけ）" >&2
  exit 1
fi

# 画面のJSは構文だけ先に見る（落ちていたら他の検査より早く分かる）
if command -v node >/dev/null 2>&1; then
  for js in web/*.js tools/*.js; do
    node --check "$js" || { echo "構文エラー: $js" >&2; exit 1; }
  done
  node tools/check-cafe-scene.js > /dev/null
  echo "  OK   web/*.js の構文と店構えSVG"
fi

FAILED=()
for check in "${CHECKS[@]}"; do
  printf '  %-32s' "$check"
  if output="$(./tools/"$check" 2>&1)"; then
    # 最後の行（各検査の結論）だけ出す。詳細は個別に走らせれば見られる
    echo "$(echo "$output" | grep -vE 'security update|baselineは|起動は続けます|ビルド' | tail -1)"
  else
    echo "失敗"
    FAILED+=("$check")
    echo "$output" | tail -20 | sed 's/^/      /'
  fi
done

echo ""
if [[ ${#FAILED[@]} -gt 0 ]]; then
  echo "失敗 ${#FAILED[@]}本: ${FAILED[*]}"
  exit 1
fi
echo "速い検査 ${#CHECKS[@]}本すべて合格（教材を触ったなら ./tools/verify-solutions.sh も走らせること）"
