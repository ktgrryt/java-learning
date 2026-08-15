# TLS検証演習

本番運用・セキュリティ編『本番セキュリティとAPI契約』のレッスン「TLS・証明書・鍵を扱う」が採点に使います。

「暗号化はされているが、相手を確認していない」状態を、実際のTLS接続で作って直す演習です。
必要なのはJDKだけです（`keytool`は同梱、ネットワークもcontainerも要りません）。

編集するのは次の2ファイルで、`reference/` が模範解答です。

| ファイル | やること |
|---|---|
| `exercise/certificate.options` | サーバー証明書のdname・SAN・有効期間。接続先は`localhost` |
| `exercise/TrustConfig.java` | クライアントが相手を信じる条件。いまは全部信頼している |

`TlsServer.java`・`TlsProbe.java`は変更しません。

## 採点で何が起きるか

`keytool`で3つの証明書と1つのtruststoreを作り、JDKのHTTPSサーバーを3回起動して実際に接続します。

| 相手 | truststore | 期待 | 何を確かめているか |
|---|---|---|---|
| あなたが作った証明書 | 信頼する | **200** | SANが接続先と合っていること |
| `CN=other-api` / `SAN=dns:other.example` | 信頼する | **拒否** | 信頼済みでもホスト名が合わなければ繋がないこと |
| `SAN=dns:localhost`だがtruststoreに無い | 信頼しない | **拒否** | 信頼の起点を持たない相手を繋がないこと |

2つ目は「証明書は信頼するが、ホスト名が違う」状態です。だから拒否の理由はチェーンではなく
`No subject alternative DNS name matching localhost found` になります。3つ目はその逆で、ホスト名は
合うのに信頼の起点がありません。**この2つを分けて確かめるのが目的**です。

「すべての証明書を信頼する」ままだと、3つ目に繋がってしまうので不合格になります。

## 手で動かす場合

```sh
JQ_LAB_PORT=9443 ./run-runtime-lab.sh
```

生成物（`out/`）はコミットしません。秘密鍵を含むkeystoreは一時領域にだけ作られます。
