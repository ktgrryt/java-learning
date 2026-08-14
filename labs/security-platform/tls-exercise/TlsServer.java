import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * 採点で使うTLSサーバー。学習者は編集しない。
 *
 * 引数は port（0なら空きポートを自動で取る）と、提示する証明書のkeystoreである。
 * 起動すると実際のポートを `started <port>` として出す。
 */
public class TlsServer {
    private static final String PASSWORD = "changeit";

    public static void main(String[] args) throws Exception {
        int requestedPort = Integer.parseInt(args[0]);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(args[1]))) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance("PKIX");
        keyManagers.init(keyStore, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(
                new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/api/orders", exchange -> {
            byte[] body = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("started " + server.getAddress().getPort());
        System.out.flush();
        Thread.sleep(120_000);
    }
}
