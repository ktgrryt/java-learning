import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** runtime-labの固定HTTP検査。学習者が編集するソースには含めない。 */
public class RuntimeProbe {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        if (args[0].equals("wait")) {
            HttpResponse<String> response = get(args[1] + "/api/greeting?name=Ready");
            if (response.statusCode() != 200
                    || !response.body().contains("\"message\":\"Hello, Ready\"")) {
                System.exit(1);
            }
            return;
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;
        HttpResponse<String> success = get(args[1] + "/api/greeting?name=Java");
        failed |= report("spring-api", success.statusCode() == 200
                        && success.body().contains("\"message\":\"Hello, Java\""),
                "実サーバーのAPIがHTTP 200と期待したJSONを返しました",
                "正常APIのstatusまたはJSONが期待値と異なります");

        HttpResponse<String> invalid = get(args[1] + "/api/greeting?name=");
        failed |= report("spring-validation", invalid.statusCode() == 400,
                "空のnameを実HTTP境界で400にしました",
                "空のnameがHTTP 400になりません");

        HttpResponse<String> health = get(args[1] + "/actuator/health");
        failed |= report("spring-health", health.statusCode() == 200
                        && health.body().contains("\"status\":\"UP\""),
                "Actuator healthがHTTP 200とUPを返しました",
                "Actuator healthが公開されていないかUPではありません");
        if (failed) System.exit(1);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        return !pass;
    }
}
