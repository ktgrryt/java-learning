#!/usr/bin/env sh
set -u

port="${JQ_LAB_PORT:?JQ_LAB_PORT is required}"
password='changeit'
server_pid=''

rm -rf out
mkdir -p out/classes

fail=0
pass() { printf 'JQ_CHECK\tPASS\t%s\t%s\n' "$1" "$2"; }
bad() { printf 'JQ_CHECK\tFAIL\t%s\t%s\n' "$1" "$2"; fail=1; }
# 学習者のファイル内容がそのまま検査結果として読まれないよう、行頭の印だけ無効化する。
clean() { sed 's/^JQ_CHECK/JQ-CHECK/'; }

stop_server() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=''
  fi
}
trap 'stop_server' EXIT INT TERM

fail_all() {
  bad tls-server-cert "$1"
  bad tls-verified-connection "$1"
  bad tls-rejects-wrong-host "$1"
  bad tls-rejects-untrusted "$1"
  bad tls-stop "$1"
  exit 1
}

opt() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" exercise/certificate.options \
    | head -1 | tr -d ' \011\015'
}
dname="$(opt dname)"
san="$(opt san)"
validity="$(opt validity)"

# keytoolへ渡す値は、この演習で扱う語彙だけに限る。
case "$dname" in CN=[A-Za-z0-9.-]*) ;; *) dname='' ;; esac
case "$san" in *[!A-Za-z0-9.,:-]*|'') san='' ;; esac
case "$validity" in ''|*[!0-9]*) validity='' ;; esac
if [ -z "$dname" ] || [ -z "$san" ] || [ -z "$validity" ] \
    || [ "$validity" -lt 1 ] || [ "$validity" -gt 825 ]; then
  fail_all 'certificate.optionsのdname・san・validityを埋めてください（validityは1〜825日）'
fi

genkey() {
  keytool -genkeypair -alias server -keyalg EC -groupname secp256r1 \
    -dname "$2" -ext "SAN=$3" -validity "$4" \
    -keystore "$1" -storetype PKCS12 -storepass "$password" -keypass "$password" \
    >>out/keytool.log 2>&1
}
export_cert() {
  keytool -exportcert -alias server -keystore "$1" -storepass "$password" -rfc -file "$2" \
    >>out/keytool.log 2>&1
}
trust_cert() {
  keytool -importcert -noprompt -alias "$2" -file "$1" \
    -keystore out/trust.p12 -storetype PKCS12 -storepass "$password" >>out/keytool.log 2>&1
}

# 1. 学習者のoptionsでサーバー証明書を作る
if ! genkey out/server.p12 "$dname" "$san" "$validity"; then
  cat out/keytool.log | clean
  fail_all 'keytoolが証明書を作れません。dname・san・validityの書き方を見直してください'
fi
# 別ホスト向けの証明書と、truststoreへ入れない証明書は固定で作る
genkey out/other.p12 'CN=other-api' 'dns:other.example' 30 || fail_all '検査用の証明書を作れません'
# ホスト名は合う状態にして、拒否の理由を「truststoreに無い」だけに絞る
genkey out/untrusted.p12 'CN=cafe-api' 'dns:localhost,ip:127.0.0.1' 30 \
  || fail_all '検査用の証明書を作れません'

export_cert out/server.p12 out/server.crt || fail_all '証明書を書き出せません'
export_cert out/other.p12 out/other.crt || fail_all '証明書を書き出せません'
# server と other だけを信頼する。otherも信頼するので、拒否の理由はホスト名の不一致になる。
trust_cert out/server.crt server || fail_all 'truststoreを作れません'
trust_cert out/other.crt other || fail_all 'truststoreを作れません'

if keytool -list -v -keystore out/server.p12 -storepass "$password" 2>/dev/null \
    | grep -Fq 'DNSName: localhost'; then
  pass tls-server-cert 'localhost向けのSANを持つサーバー証明書を作りました'
else
  bad tls-server-cert '接続先はlocalhostです。SANへlocalhostを入れてください（CNでは代用できません）'
fi

if ! javac --release 21 -d out/classes \
    TlsServer.java TlsProbe.java exercise/TrustConfig.java >out/javac.log 2>&1; then
  cat out/javac.log | clean
  bad tls-verified-connection 'TrustConfigをコンパイルできません'
  bad tls-rejects-wrong-host 'TrustConfigをコンパイルできません'
  bad tls-rejects-untrusted 'TrustConfigをコンパイルできません'
  bad tls-stop 'TrustConfigをコンパイルできません'
  exit 1
fi

# 指定のkeystoreでサーバーを起動し、実際のポートを actual_port へ入れる。
# コマンド置換で呼ぶとsubshellになり、server_pidが親へ残らず停止できない。
actual_port=''
start_server() {
  keystore="$1"
  wanted="$2"
  : >out/server.log
  java -cp out/classes TlsServer "$wanted" "$keystore" >out/server.log 2>&1 &
  server_pid=$!
  attempt=0
  while [ "$attempt" -lt 20 ]; do
    actual_port="$(sed -n 's/^started //p' out/server.log | head -1)"
    if [ -n "$actual_port" ]; then return 0; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then return 1; fi
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

probe() {
  java -cp out/classes TlsProbe "$1" "https://localhost:$2/api/orders" out/trust.p12 2>&1
}

# 2. 正しい証明書のサーバーへ、検証を効かせたまま接続できる
start_server out/server.p12 "$port" || fail_all 'TLSサーバーを起動できません'
reason="$(probe expect-ok "$actual_port" | head -1 | clean)"
if [ -n "$reason" ] && printf '%s' "$reason" | grep -Fq '接続できました'; then
  pass tls-verified-connection '正しい証明書のサーバーへHTTPS接続し、200を受け取りました'
else
  bad tls-verified-connection "正しい証明書のサーバーへ接続できません（${reason}）"
fi

# 5. 停止すると応答しない
stop_server
reason="$(probe expect-closed "$actual_port" | head -1 | clean)"
if printf '%s' "$reason" | grep -Fq '接続を受け付けません'; then
  pass tls-stop '検証後にTLS listenerが停止していることを確認しました'
else
  bad tls-stop "停止後もlistenerが応答します（${reason}）"
fi

# 3. 別ホスト向けの証明書は、信頼済みでも拒否する
start_server out/other.p12 0 || fail_all '検査用サーバーを起動できません'
reason="$(probe expect-reject "$actual_port" | head -1 | clean)"
stop_server
if printf '%s' "$reason" | grep -Fq '拒否しました'; then
  pass tls-rejects-wrong-host '別ホスト向けの証明書を、信頼済みでもホスト名の不一致で拒否しました'
else
  bad tls-rejects-wrong-host "別ホスト向けの証明書を拒否できていません（${reason}）"
fi

# 4. truststoreに無い証明書は拒否する
start_server out/untrusted.p12 0 || fail_all '検査用サーバーを起動できません'
reason="$(probe expect-reject "$actual_port" | head -1 | clean)"
stop_server
if printf '%s' "$reason" | grep -Fq '拒否しました'; then
  pass tls-rejects-untrusted 'truststoreに無い証明書を拒否しました'
else
  bad tls-rejects-untrusted "truststoreに無い証明書を拒否できていません（${reason}）"
fi

exit "$fail"
