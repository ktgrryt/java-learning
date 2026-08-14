import javax.net.ssl.SSLContext;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 採点で使うHTTPS検査。学習者は編集しない。
 *
 * 接続の可否は学習者の {@code TrustConfig} が作るSSLContextで決まる。
 * 使い方: TlsProbe (expect-ok|expect-reject|expect-closed) URL truststore
 */
public class TlsProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String url = args[1];
        Path truststore = Path.of(args[2]);

        SSLContext context;
        try {
            context = TrustConfig.create(truststore);
        } catch (Exception e) {
            System.out.println("TrustConfig.createが失敗しました: " + summarize(e));
            System.exit(1);
            return;
        }
        if (context == null) {
            System.out.println("TrustConfig.createがnullを返しました");
            System.exit(1);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .sslContext(context)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200
                    && response.body().replaceAll("\\s+", "").equals("{\"status\":\"OK\"}");
            if (mode.equals("expect-ok")) {
                System.out.println(ok ? "接続できました" : "応答が期待と違います: " + response.statusCode());
                System.exit(ok ? 0 : 1);
            } else {
                // 拒否されるはずの相手へ繋がってしまった。検証が働いていない。
                System.out.println("接続できてしまいました（status=" + response.statusCode() + "）");
                System.exit(1);
            }
        } catch (Exception e) {
            String reason = summarize(e);
            if (mode.equals("expect-ok")) {
                System.out.println("接続できません: " + reason);
                System.exit(1);
            }
            if (mode.equals("expect-closed")) {
                boolean closed = hasCause(e, ConnectException.class);
                System.out.println(closed ? "接続を受け付けません: " + reason
                        : "停止後の応答が想定と違います: " + reason);
                System.exit(closed ? 0 : 1);
            }
            System.out.println("拒否しました: " + reason);
            System.exit(0);
        }
    }

    private static boolean hasCause(Throwable error, Class<?> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    /** 失敗理由を1行へ。検査結果の書式を壊す文字は落とす。 */
    private static String summarize(Throwable error) {
        Throwable deepest = error;
        while (deepest.getCause() != null) deepest = deepest.getCause();
        String message = deepest.getClass().getSimpleName()
                + (deepest.getMessage() == null ? "" : ": " + deepest.getMessage());
        String single = message.replaceAll("[\\r\\n\\t]", " ");
        return single.length() <= 140 ? single : single.substring(0, 140) + "...";
    }
}
