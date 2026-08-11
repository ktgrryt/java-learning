# TLS・OIDC・API securityラボ

## 必要なもの

- JDK 21以降（`keytool` はJDKに同梱。1章はこれだけで試せます）
- 2章はテスト用のIdentity Provider、または署名鍵を自分で作る手段
- 3章はOpenAPIのlinter（`spectral` など）とSBOM/脆弱性スキャンのツール

> 1章（`keytool`）は JDK 21.0.8 / macOS 15 で動作確認。2章・3章は使うツールに
> 依存するため未確認です。

## 1. test用CAと証明書を観察する

本物の秘密鍵をリポジトリへ保存しないでください。一時ディレクトリで `keytool` を使い、
keystoreとtruststoreの役割、SAN、期限、chainを確認します。

```bash
keytool -genkeypair -alias local-server -keyalg EC -groupname secp256r1 \
  -dname "CN=localhost" -ext SAN=dns:localhost,ip:127.0.0.1 \
  -validity 30 -keystore local-server.p12 -storetype PKCS12
keytool -list -v -keystore local-server.p12
```

### 成功したらこう出る

1つ目のコマンドはパスワードを尋ねたあと、次のように答えます。

```
30日間有効な256ビットのEC (secp256r1)のキー・ペアと自己署名型証明書(SHA384withECDSA)を生成しています
```

`keytool -list -v` では、確認したい項目がそろって出ます。

```
別名: local-server
エントリ・タイプ: PrivateKeyEntry
所有者: CN=localhost
署名アルゴリズム名: SHA384withECDSA
SubjectAlternativeName [
  DNSName: localhost
```

`PrivateKeyEntry`（秘密鍵を持っている＝サーバー側のkeystore）であること、
SANに `localhost` が入っていること、有効期限が30日であることを確かめます。
SANが無い証明書は、最近のクライアントではホスト名の検証に失敗します。

作成したファイルは使い終えたら安全に削除し、パスワードをシェルの履歴やソースへ残しません。

## 2. OIDC/JWTの拒否testを先に書く

resource serverのテスト用identity providerまたは署名鍵を使い、次を **すべて拒否できるか**
確認します。「通ること」より「通らないこと」を先にテストします。

- 不正署名、許可していないalgorithm、未知の `kid`
- issuer違い、audience違い、期限切れ、未来の `nbf`
- scope不足、tenant/owner不一致
- tokenなし、tokenをcookieで扱う場合のCSRF

秘密鍵や本番tokenをfixtureにせず、token本文をログへ出しません。

## 3. API契約と供給網

`openapi.yaml` をlintし、provider/consumer contract testを作ります。CIでは依存treeとSBOMを保存し、
既知脆弱性発生時に「どの成果物へ入ったか」を検索する演習を行います。scanの抑制には担当者、根拠、
期限を付けます。
