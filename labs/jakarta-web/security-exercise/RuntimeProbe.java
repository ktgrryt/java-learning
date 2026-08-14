import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** runtime-labの固定HTTP検査。学習者は編集しない。 */
public class RuntimeProbe {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            // 401のときに勝手に再認証させない（401をそのまま観測したい）
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        String base = args[1] + "/api";

        if (args[0].equals("wait")) {
            if (get(base + "/health", null).statusCode() == 404) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                get(base + "/health", null);
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;

        // ── 1. 公開のままにすべき場所が閉じられていないか ────────────────────
        HttpResponse<String> health = get(base + "/health", null);
        failed |= report("rest-public", health.statusCode() == 200,
                "生存確認は資格情報なしで200のままでした",
                "生存確認が" + health.statusCode()
                        + "になっています。役割を要求するのは守るべき場所だけにしてください");

        // ── 2. 資格情報なしで一覧が見えないか ───────────────────────────────
        HttpResponse<String> anonymous = get(base + "/orders", null);
        failed |= report("rest-unauthenticated", anonymous.statusCode() == 401,
                "資格情報なしの一覧要求が401になりました",
                "資格情報なしで" + anonymous.statusCode() + "が返りました（"
                        + shorten(anonymous.body()) + "）"
                        + " … 一覧にstaffの役割を要求してください");

        // ── 3. 内部の項目が外へ出ていないか ─────────────────────────────────
        HttpResponse<String> listed = get(base + "/orders", basic("aki", "aki-pass"));
        String body = listed.body();
        boolean hasPublicFields = body.contains("ORD-1") && body.contains("エスプレッソ");
        boolean leaks = body.contains("internalNote") || body.contains("常連")
                || body.contains("支払い遅延") || body.contains("customer");
        failed |= report("rest-no-leak", listed.statusCode() == 200 && hasPublicFields && !leaks,
                "一覧はstaffで200を返し、社内メモと顧客名を含んでいませんでした",
                "状態=" + listed.statusCode() + " 本文=" + shorten(body)
                        + " … 内部の形をそのまま返さず、外向きの形へ移し替えてください");

        // ── 4. 役割が足りないときに403になるか ──────────────────────────────
        HttpResponse<String> staffDelete = delete(base + "/orders/ORD-2", basic("aki", "aki-pass"));
        HttpResponse<String> managerDelete =
                delete(base + "/orders/ORD-2", basic("mgr", "mgr-pass"));
        boolean forbidden = staffDelete.statusCode() == 403
                && (managerDelete.statusCode() == 204 || managerDelete.statusCode() == 200);
        failed |= report("rest-forbidden", forbidden,
                "staffだけの利用者の削除は403、managerの削除は成功しました",
                "staffでの削除=" + staffDelete.statusCode() + "（期待403）"
                        + " / managerでの削除=" + managerDelete.statusCode() + "（期待204）"
                        + " … 削除にmanagerの役割を要求してください");

        if (failed) System.exit(1);
    }

    // ---- HTTP の道具 --------------------------------------------------------

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> get(String url, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return CLIENT.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> delete(String url, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).DELETE();
        if (authorization != null) request.header("Authorization", authorization);
        return CLIENT.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String shorten(String text) {
        String single = text == null ? "" : text.replaceAll("[\\r\\n\\t]", " ");
        return single.length() <= 160 ? single : single.substring(0, 160) + "...";
    }

    private static boolean report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        return !pass;
    }
}
