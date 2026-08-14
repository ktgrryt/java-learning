import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * {@link OrderClient} を実サーバへ当てて、再試行のしかたを測る足場。参照専用。
 *
 * <p>試行回数はclientの自己申告ではなく、{@link FlakyServer} が数えた受信回数で確かめる。
 * 「3回試した」とログに書くだけでは通らない。
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class RetryHarness {

    public static void main(String[] args) {
        String baseUrl = args[0];
        OrderClient client = new OrderClient(baseUrl);

        // ── 一時的な失敗（503が2回 → 3回目で成功）────────────────────────
        long startedAt = System.nanoTime();
        try {
            String body = client.fetch("/transient", "cid-transient");
            print("transient-outcome", body.contains("A-1001") ? "ok" : "unexpected-body:" + body);
        } catch (Exception gaveUp) {
            print("transient-outcome", "gave-up:" + gaveUp.getMessage());
        }
        print("transient-millis", String.valueOf(millisSince(startedAt)));

        // ── 恒久的な失敗（400。再試行してはいけない）──────────────────────
        startedAt = System.nanoTime();
        try {
            String body = client.fetch("/permanent", "cid-permanent");
            print("permanent-outcome", "returned:" + body);
        } catch (Exception gaveUp) {
            print("permanent-outcome", "threw");
        }
        print("permanent-millis", String.valueOf(millisSince(startedAt)));

        // ── 遅い応答（4秒。打ち切らないと待たされる）─────────────────────
        startedAt = System.nanoTime();
        try {
            String body = client.fetch("/slow", "cid-slow");
            print("slow-outcome", "returned:" + body);
        } catch (Exception gaveUp) {
            print("slow-outcome", "threw");
        }
        print("slow-millis", String.valueOf(millisSince(startedAt)));

        // ── 受け取った側が数えた回数 ──────────────────────────────────
        print("server-counts", counts(baseUrl));

        // ── 構造化ログ ───────────────────────────────────────────────
        List<String> lines = client.logLines();
        print("log-count", String.valueOf(lines.size()));
        for (int i = 0; i < lines.size(); i++) {
            print("log-" + i, lines.get(i));
        }
    }

    /** サーバが数えた受信回数を読む（clientの実装とは無関係に測るため）。 */
    private static String counts(String baseUrl) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/counts"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body().trim();
        } catch (Exception failed) {
            return "unavailable:" + failed;
        }
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t"
                + String.valueOf(value).replace('\t', ' ').replace('\n', ' '));
    }
}
