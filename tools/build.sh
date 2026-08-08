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

# ---- 使うJDKを1つ決める ----------------------------------------------------
jq_resolve_jdk() {
  local candidates=() bin version

  # 明示指定 > JAVA_HOME > PATH の javac > macOS の既定
  [[ -n "${JQ_JAVA_HOME:-}" ]] && candidates+=("$JQ_JAVA_HOME/bin")
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin")
  if command -v javac >/dev/null 2>&1; then
    candidates+=("$(cd "$(dirname "$(command -v javac)")" && pwd)")
  fi
  if [[ -x /usr/libexec/java_home ]]; then
    local home
    home="$(/usr/libexec/java_home 2>/dev/null || true)"
    [[ -n "$home" ]] && candidates+=("$home/bin")
  fi

  for bin in "${candidates[@]}"; do
    [[ -x "$bin/javac" && -x "$bin/java" ]] || continue
    version="$("$bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
    [[ "$version" =~ ^[0-9]+$ ]] || continue
    (( version >= JQ_TARGET_RELEASE )) || continue

    JQ_JAVAC="$bin/javac"
    JQ_JAVA="$bin/java"
    JQ_JDK_LABEL="$("$bin/javac" -version 2>&1) (release $JQ_TARGET_RELEASE)"
    return 0
  done
  return 1
}

if ! jq_resolve_jdk; then
  echo "エラー: JDK ${JQ_TARGET_RELEASE} 以降が見つかりません。" >&2
  echo "" >&2
  echo "  javac が必要です（JRE だけでは足りません。あなたのコードをコンパイルするため）。" >&2
  echo "  例: brew install openjdk@21" >&2
  echo "" >&2
  echo "  すでに入っている場合は、そのJDKの場所を指定してください:" >&2
  echo "    JQ_JAVA_HOME=/path/to/jdk ./run.sh" >&2
  exit 1
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
  "$JQ_JAVAC" --release "$JQ_TARGET_RELEASE" -encoding UTF-8 -d "$build_dir" @build/sources.txt
  printf '%s' "$JQ_JDK_LABEL" > "$stamp"
  touch "$build_dir"
  echo "ビルド完了"
}
