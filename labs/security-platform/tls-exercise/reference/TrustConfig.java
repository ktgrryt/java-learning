import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * HTTPSクライアントが「相手を信じるかどうか」を決める設定。
 *
 * 渡されたtruststoreに入っている証明書だけを信頼する。JDKが用意した
 * TrustManagerFactory を使うので、chainの検証もホスト名の照合も既定で働く。
 */
public final class TrustConfig {
    private static final String PASSWORD = "changeit";

    private TrustConfig() {
    }

    public static SSLContext create(Path truststore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance("PKIX");
        trustManagers.init(store);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
