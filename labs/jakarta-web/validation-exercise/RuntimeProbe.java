import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** runtime-labの固定HTTP検査。学習者は編集しない。 */
public class RuntimeProbe {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static final String VALID = """
            {"item":"エスプレッソ","quantity":2,"totalYen":1200,"couponCode":"SAVE10"}""";

    /** 品名が空白だけ・数量0・合計0。3項目が同時に違反する。 */
    private static final String INVALID = """
            {"item":"   ","quantity":0,"totalYen":0,"couponCode":"SAVE10"}""";

    /** 1項目ずつ見れば正しいが、組み合わせが不正（クーポンありで1000円未満）。 */
    private static final String CROSS_FIELD = """
            {"item":"ドリップ","quantity":1,"totalYen":500,"couponCode":"SAVE10"}""";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        String orders = args[1] + "/api/orders";

        if (args[0].equals("wait")) {
            if (post(orders, VALID).statusCode() == 404) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                post(orders, VALID);
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;

        // ── 1. 正しい入力は通るか ──────────────────────────────────────────
        HttpResponse<String> accepted = post(orders, VALID);
        String acceptedBody = accepted.body();
        boolean ok = accepted.statusCode() == 201 && acceptedBody.contains("\"id\":\"ORD-")
                && acceptedBody.contains("エスプレッソ");
        failed |= report("validation-accepts", ok,
                "正しい注文が201で受け付けられました",
                "正しい注文が通りません（" + accepted.statusCode() + " " + shorten(acceptedBody) + "）");

        // ── 2. 宣言した制約が実際に効いているか ────────────────────────────
        HttpResponse<String> rejected = post(orders, INVALID);
        failed |= report("validation-rejects", rejected.statusCode() == 400,
                "空白だけの品名・数量0・合計0がHTTP 400で拒否されました",
                "不正な入力が" + rejected.statusCode() + "で通ってしまいました（"
                        + shorten(rejected.body()) + "）"
                        + " … 制約を宣言し、Resource側で有効にしてください");

        // ── 3. 項目間のルールが効いているか ────────────────────────────────
        HttpResponse<String> cross = post(orders, CROSS_FIELD);
        boolean crossOk = cross.statusCode() == 400 && cross.body().contains("couponUsable");
        failed |= report("validation-cross-field", crossOk,
                "クーポンありで1000円未満の組み合わせが、couponUsableの違反として400になりました",
                "組み合わせの不正が検出されていません（" + cross.statusCode() + " "
                        + shorten(cross.body()) + "）"
                        + " … 1項目では判定できないルールをまとめて宣言してください");

        // ── 4. 応答が「直せる情報」になっているか ───────────────────────────
        String body = rejected.body();
        boolean namesFields = body.contains("\"field\":\"item\"")
                && body.contains("\"field\":\"quantity\"")
                && body.contains("\"field\":\"totalYen\"");
        boolean leaks = body.contains("arg0") || body.contains("Exception")
                || body.contains("cafe.api.") || body.contains("\tat ");
        boolean sorted = order(body, "\"field\":\"item\"") < order(body, "\"field\":\"quantity\"")
                && order(body, "\"field\":\"quantity\"") < order(body, "\"field\":\"totalYen\"");
        failed |= report("validation-error-body", namesFields && !leaks && sorted,
                "400の本文が、項目名の昇順で「どの項目がなぜ悪いか」を返し、内部の作りを漏らしていません",
                "本文=" + shorten(body)
                        + " … 項目名（item / quantity / totalYen）を昇順で並べ、"
                        + "arg0や例外の型名を含めない形にしてください");

        if (failed) System.exit(1);
    }

    private static int order(String body, String needle) {
        int at = body.indexOf(needle);
        return at < 0 ? Integer.MAX_VALUE : at;
    }

    private static HttpResponse<String> post(String url, String json) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
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
