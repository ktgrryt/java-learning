#!/usr/bin/env bash
#
# ビルド処理。run.sh と tools/verify-solutions.sh が source して使う。
#
# このマシンにJDKが複数入っていることは珍しくない（Homebrew / sdkman / IDE付属など）。
# javac と java が別のJDKだと "UnsupportedClassVersionError" で起動できないので、
# 必ず同じJDKの javac と java を使い、さらに --release で古いJDKでも動く形に落とす。
#
# source すると次を定義する:
#   JQ_JAVAC / JQ_JAVA … 使うJDKの実行ファイル
#   JQ_JDK_LABEL       … 表示・スタンプ用のバージョン文字列
#   jq_build           … 必要なときだけビルドする関数

# 出力クラスが動く最低バージョン。ここを上げると古いJDKで動かなくなる
JQ_TARGET_RELEASE=21

# javacの警告。既定では黙っているものまで出す。
#
# 説明の付け替え漏れ（dangling-doc-comments）のように、動くけれど読み手を誤らせる
# 間違いはこれでしか出ない。実際に、後から挿し込んだメソッドの上に別のメソッドの説明が
# 残っていた箇所が2件あった。警告0の状態から始めるので、増えたらすぐ分かる。
# `-Werror` にはしない ― 学習者が起動できなくなるのは割に合わない。
JQ_LINT=(-Xlint:all)

# 2026-07-21のJava CPU（Critical Patch Update）で公開された最低security baseline。
# 四半期ごとのCPUに合わせて更新する。非LTSの22〜24は現在の更新対象外なので受け付けない。
jq_jdk_meets_security_baseline() {
  local raw="$1" feature update minimum
  if [[ ! "$raw" =~ ^([0-9]+)(\.0\.([0-9]+))? ]]; then
    return 1
  fi
  feature="${BASH_REMATCH[1]}"
  update="${BASH_REMATCH[3]:-0}"
  case "$feature" in
    21) minimum=12 ;;
    25) minimum=4 ;;
    26) minimum=2 ;;
    *) (( feature > 26 )) && return 0 || return 1 ;;
  esac
  (( update >= minimum ))
}

# ---- 使うJDKを1つ決める ----------------------------------------------------
jq_resolve_jdk() {
  local candidates=() bin version full_version home

  # 明示指定 > JAVA_HOME > PATH の javac > Homebrew > macOS の既定
  [[ -n "${JQ_JAVA_HOME:-}" ]] && candidates+=("$JQ_JAVA_HOME/bin")
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin")
  if command -v javac >/dev/null 2>&1; then
    candidates+=("$(cd "$(dirname "$(command -v javac)")" && pwd)")
  fi
  # HomebrewのOpenJDKはmacOSのjava_homeへ登録されず、PATHにも自動で入らないことがある。
  # security update済みのJDKが既にあるのに古いApple側の既定だけを見る事態を避ける。
  for home in \
      /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin \
      /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin \
      /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin \
      /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin; do
    [[ -d "$home" ]] && candidates+=("$home")
  done
  if [[ -x /usr/libexec/java_home ]]; then
    home="$(/usr/libexec/java_home 2>/dev/null || true)"
    [[ -n "$home" ]] && candidates+=("$home/bin")
  fi

  for bin in "${candidates[@]}"; do
    [[ -x "$bin/javac" && -x "$bin/java" ]] || continue
    full_version="$("$bin/javac" -version 2>&1 | awk '{print $2}')"
    version="${full_version%%.*}"
    [[ "$version" =~ ^[0-9]+$ ]] || continue
    (( version >= JQ_TARGET_RELEASE )) || continue

    JQ_JAVAC="$bin/javac"
    JQ_JAVA="$bin/java"
    JQ_JDK_LABEL="$("$bin/javac" -version 2>&1) (release $JQ_TARGET_RELEASE)"
    if ! jq_jdk_meets_security_baseline "$full_version"; then
      JQ_JDK_SECURITY_WARNING="$full_version"
    else
      JQ_JDK_SECURITY_WARNING=""
    fi
    return 0
  done
  return 1
}

if ! jq_resolve_jdk; then
  echo "エラー: 対応するJDK ${JQ_TARGET_RELEASE} 以降が見つかりません。" >&2
  echo "" >&2
  echo "  javac が必要です（JRE だけでは足りません。あなたのコードをコンパイルするため）。" >&2
  echo "  例: brew install openjdk@21（インストール後もsecurity updateを適用してください）" >&2
  echo "" >&2
  echo "  すでに入っている場合は、そのJDKの場所を指定してください:" >&2
  echo "    JQ_JAVA_HOME=/path/to/jdk ./run.sh" >&2
  exit 1
fi

if [[ -n "$JQ_JDK_SECURITY_WARNING" ]]; then
  echo "警告: 使用するJDK $JQ_JDK_SECURITY_WARNING はsecurity updateが古い可能性があります。" >&2
  echo "      2026年7月CPUのbaselineは JDK 21.0.12 / 25.0.4 / 26.0.2 以降です。" >&2
  echo "      起動は続けますが、同じfeature versionの最新security updateを推奨します。" >&2
fi

# ---- 必要なときだけビルドする ----------------------------------------------
jq_build() {
  local build_dir="build/classes"
  local stamp="build/.jdk"
  local needs_build=0

  if [[ ! -d "$build_dir" ]]; then
    needs_build=1
  elif [[ ! -f "$stamp" ]] || [[ "$(cat "$stamp")" != "$JQ_JDK_LABEL" ]]; then
    # 前回と違うJDKでビルドされている。混ざると起動できないので作り直す
    needs_build=1
  elif [[ -n "$(find src -name '*.java' -newer "$build_dir" -print -quit)" ]]; then
    needs_build=1
  fi

  if [[ "$needs_build" == "0" ]]; then
    return 0
  fi

  echo "ビルド中... ($JQ_JDK_LABEL)"
  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  find src -name '*.java' > build/sources.txt
  "$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 "${JQ_LINT[@]}" \
    -d "$build_dir" @build/sources.txt
  printf '%s' "$JQ_JDK_LABEL" > "$stamp"
  touch "$build_dir"
  echo "ビルド完了"
}
