import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** runtime-labの固定HTTP検査。学習者は編集しない。 */
public class RuntimeProbe {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        if (args[0].equals("wait")) {
            HttpResponse<String> response = get(args[1] + "/api/greeting?name=Ready");
            if (response.statusCode() != 200) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                get(args[1] + "/api/greeting?name=Stopped");
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;
        HttpResponse<String> success = get(args[1] + "/api/greeting?name=Java");
        failed |= report("quarkus-api", success.statusCode() == 200
                        && success.body().trim().equals("{\"message\":\"Hello, Java\"}"),
                "JVMモードのQuarkus RESTがHTTP 200と期待したJSONを返しました",
                "RESTのstatusまたはJSONが期待値と異なります");

        HttpResponse<String> blank = get(args[1] + "/api/greeting?name=");
        HttpResponse<String> longName = get(args[1] + "/api/greeting?name=aaaaaaaaaaaaaaaaaaaaa");
        failed |= report("quarkus-validation", blank.statusCode() == 400 && longName.statusCode() == 400,
                "Hibernate Validator Extensionが空文字と21文字をHTTP 400にしました",
                "空文字または21文字のnameがHTTP 400になりません");

        HttpResponse<String> health = get(args[1] + "/q/health/ready");
        String body = health.body().replaceAll("\\s+", "");
        failed |= report("quarkus-health", health.statusCode() == 200
                        && body.contains("\"status\":\"UP\"")
                        && body.contains("\"name\":\"greeting\""),
                "SmallRye Health readinessがgreeting=UPを返しました",
                "readinessがHTTP 200のgreeting=UPを返していません");
        if (failed) System.exit(1);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3)).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean report(String id, boolean pass, String ok, String ng) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id, pass ? ok : ng);
        return !pass;
    }
}
