import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** runtime-labの固定HTTP検査。学習者は編集しない。 */
public class RuntimeProbe {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static final String VALID = """
            {"name":" Aki ","email":"aki@example.test","registeredAt":"2026-08-13T09:00:00Z"}""";
    private static final String INVALID = """
            {"name":" ","email":"not-an-email","registeredAt":"2026-08-13T09:00:00Z"}""";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        String users = args[1] + "/api/users";

        if (args[0].equals("wait")) {
            // 配備前は404になる。応答さえ返れば、中身の判定はverifyへ任せる。
            if (post(users, VALID).statusCode() == 404) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                post(users, VALID);
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;
        HttpResponse<String> created = post(users, VALID);
        String body = compact(created.body());
        failed |= report("ee11-created", created.statusCode() == 201
                        && body.contains("\"name\":\"Aki\"")
                        && body.contains("\"email\":\"aki@example.test\""),
                "新規作成がHTTP 201を返し、recordがgetter無しでJSONへ変換されました",
                "HTTP 201と、前後の空白を除いた名前・メールを含むJSONを返してください（実際: "
                        + created.statusCode() + " " + shorten(body) + "）");

        failed |= report("ee11-instant",
                body.contains("\"registeredAt\":\"2026-08-13T09:00:00Z\""),
                "InstantがISO-8601の文字列としてJSONへ書き出されました",
                "応答へregisteredAtをInstantとして入れてください（実際: " + shorten(body) + "）");

        HttpResponse<String> invalid = post(users, INVALID);
        failed |= report("ee11-validation", invalid.statusCode() == 400,
                "Bean Validationが空の名前と形式違反のメールをHTTP 400にしました",
                "空の名前と形式違反のメールをHTTP 400で拒否してください（実際: "
                        + invalid.statusCode() + "）");

        if (failed) System.exit(1);
    }

    private static HttpResponse<String> post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 引用符の外の空白だけを落とす。
     *
     * 一律に空白を消すと `" Aki "` が `"Aki"` になり、境界で値を整えたかどうかを
     * 検査できなくなる。
     */
    private static String compact(String body) {
        StringBuilder out = new StringBuilder(body.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 失敗理由へ入れる実測値。改行やタブは検査結果の書式を壊すので落とす。 */
    private static String shorten(String body) {
        String single = body.replaceAll("[\\r\\n\\t]", " ");
        return single.length() <= 120 ? single : single.substring(0, 120) + "...";
    }

    private static boolean report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        return !pass;
    }
}
