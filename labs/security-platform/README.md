# TLS・OIDC・API securityラボ

## 1. test用CAと証明書を観察する

本物の秘密鍵をrepositoryへ保存しないでください。一時directoryで `keytool` を使い、
keystoreとtruststoreの役割、SAN、期限、chainを確認します。

```bash
keytool -genkeypair -alias local-server -keyalg EC -groupname secp256r1 \
  -dname "CN=localhost" -ext SAN=dns:localhost,ip:127.0.0.1 \
  -validity 30 -keystore local-server.p12 -storetype PKCS12
keytool -list -v -keystore local-server.p12
```

作成したfileは使い終えたら安全に削除し、passwordをshell historyやsourceへ残しません。

## 2. OIDC/JWTの拒否testを先に書く

resource serverのtest用identity providerまたは署名鍵を使い、次をすべて拒否できるか確認します。

- 不正署名、許可していないalgorithm、未知の`kid`
- issuer違い、audience違い、期限切れ、未来の`nbf`
- scope不足、tenant/owner不一致
- tokenなし、tokenをcookieで扱う場合のCSRF

秘密鍵や本番tokenをfixtureにせず、token本文をlogへ出しません。

## 3. API契約と供給網

`openapi.yaml` をlintし、provider/consumer contract testを作ります。CIでは依存treeとSBOMを保存し、
既知脆弱性発生時に「どの成果物へ入ったか」を検索する演習を行います。scanの抑制には担当者、根拠、
期限を付けます。

