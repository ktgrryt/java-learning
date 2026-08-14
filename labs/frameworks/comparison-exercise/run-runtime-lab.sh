#!/usr/bin/env sh
set -u

rm -rf out
mkdir -p out

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }

fail_all() {
  for id in compare-built compare-runtime-origin compare-artifact-shape \
      compare-constraint-first compare-reason; do
    printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$id" "$1"
  done
  exit 1
}

# 3製品を同じ手順でbuildする。testは走らせない（ここで見るのは成果物の形）。
for product in spring liberty quarkus; do
  if ! ( cd "$product" && mvn -B -q package -DskipTests ) >"out/${product}.log" 2>&1; then
    tail -25 "out/${product}.log" | sed 's/^JQ_CHECK/JQ-CHECK/'
    fail_all "${product}のbuildが失敗しました。初回は依存の取得に時間がかかります"
  fi
done

spring_jar='spring/target/orders-spring.jar'
liberty_war='liberty/target/orders-liberty.war'
quarkus_dir='quarkus/target/quarkus-app'

# 1. 3つの成果物ができたか
if [ -s "$spring_jar" ] && [ -s "$liberty_war" ] && [ -f "$quarkus_dir/quarkus-run.jar" ]; then
  pass compare-built '3製品の成果物（fat JAR・WAR・quarkus-app）ができました'
else
  bad compare-built '3製品の成果物がそろいません'
  fail_all '成果物がないため比較できません'
fi

# --- 成果物から事実を測る。答えは教材へ書かず、ここで測った値と比べる。 ---
measured_runtime() {
  case "$1" in
    spring) jar tf "$spring_jar" | grep -q '^BOOT-INF/lib/.*\.jar$' && echo bundled || echo server ;;
    liberty) [ "$(jar tf "$liberty_war" | grep -c '^WEB-INF/lib/.*\.jar$')" -gt 0 ] \
        && echo bundled || echo server ;;
    quarkus) [ -d "$quarkus_dir/lib" ] && echo bundled || echo server ;;
  esac
}
measured_shape() {
  case "$1" in
    # 単体で起動できるJAR（起動用loaderを含む）
    spring) jar tf "$spring_jar" | grep -q '^org/springframework/boot/loader/' \
        && echo single-jar || echo unknown ;;
    liberty) case "$liberty_war" in *.war) echo war ;; *) echo unknown ;; esac ;;
    # 一式そろって初めて起動できるディレクトリ
    quarkus) [ -f "$quarkus_dir/quarkus-run.jar" ] && [ -d "$quarkus_dir/lib" ] \
        && echo directory || echo unknown ;;
  esac
}

answer() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" exercise/comparison.properties \
    | head -1 | tr -d '\015' | sed 's/[[:space:]]*$//'
}

# 2. 実装の出所が実測と合っているか
origin_ok=1
origin_detail=''
for product in spring liberty quarkus; do
  want="$(measured_runtime "$product")"
  got="$(answer "runtime\\.${product}")"
  [ "$got" = "$want" ] || origin_ok=0
  origin_detail="${origin_detail}${product}=${want} "
done
if [ "$origin_ok" -eq 1 ]; then
  pass compare-runtime-origin "実装の出所が成果物と一致しました（${origin_detail}）"
else
  # 二重引用符の中でバックティックを書くとコマンド置換になる。ここは単一引用符で固定する。
  bad compare-runtime-origin 'runtime.*が成果物と合っていません。jar tfでBOOT-INF/libとWEB-INF/libの中身を、ls quarkus/target/quarkus-app/libを見比べてください'
fi

# 3. 成果物の形が実測と合っているか
shape_ok=1
shape_detail=''
for product in spring liberty quarkus; do
  want="$(measured_shape "$product")"
  got="$(answer "artifact\\.${product}")"
  [ "$got" = "$want" ] || shape_ok=0
  shape_detail="${shape_detail}${product}=${want} "
done
if [ "$shape_ok" -eq 1 ]; then
  pass compare-artifact-shape "成果物の形が実測と一致しました（${shape_detail}）"
else
  bad compare-artifact-shape "artifact.*が実測と合っていません。単体で起動できるJARか、サーバーへ載せるWARか、一式そろって初めて動くディレクトリかを確かめてください"
fi

# 4. 必須条件を先に適用できているか。同梱している製品は条件を満たさない。
rejected=''
for product in liberty quarkus spring; do
  if [ "$(measured_runtime "$product")" = bundled ]; then
    rejected="${rejected}${product},"
  fi
done
rejected="$(printf '%s' "$rejected" | sed 's/,$//')"
answered="$(answer 'decision\.rejected' | tr -d ' ' | tr ',' '\n' | sort | paste -sd, -)"
if [ "$answered" = "$rejected" ]; then
  pass compare-constraint-first "必須条件を先に適用し、外れる製品を正しく挙げました（${rejected}）"
else
  bad compare-constraint-first "必須条件は「実装をサーバー側が提供する」です。実装を成果物へ同梱している製品は、この条件を満たしません（回答: ${answered:-空}）"
fi

# 5. 結論の根拠に、実測した成果物の中身が挙がっているか
reason="$(answer 'decision\.reason')"
if printf '%s' "$reason" | grep -Eq 'BOOT-INF|WEB-INF|quarkus-app|server\.xml'; then
  pass compare-reason '結論の根拠に、実測した成果物の中身が挙がっています'
else
  bad compare-reason 'decision.reasonへ、実測して確かめた成果物の中身（BOOT-INF・WEB-INF・quarkus-app・server.xmlなど）を挙げてください'
fi

printf '%s\n' '--- 見るところ（答えは出しません） ---'
printf '%s\n' "jar tf ${spring_jar} | grep -E 'BOOT-INF/|loader/' | head"
printf '%s\n' "jar tf ${liberty_war}"
printf '%s\n' "cat liberty/src/main/liberty/config/server.xml"
printf '%s\n' "ls ${quarkus_dir} && ls ${quarkus_dir}/lib"
exit "$fail"
