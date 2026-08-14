import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

/**
 * HTTPSクライアントが「相手を信じるかどうか」を決める設定。
 *
 * いまは検証を全部やめている。動作確認のために書かれ、そのまま残った形である。
 * これでは、偽のサーバーへ繋いでも、別のホスト向けの証明書を出されても気づけない。
 *
 * TODO: 渡された truststore（PKCS12、パスワードは "changeit"）だけを信頼するように直す。
 *       KeyStore で読み、TrustManagerFactory へ渡す。自分でX509TrustManagerを書く必要はない。
 */
public final class TrustConfig {

    private TrustConfig() {
    }

    public static SSLContext create(Path truststore) throws Exception {
        TrustManager[] trustEverything = {
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustEverything, null);
        return context;
    }
}
