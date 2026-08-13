import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class NativeProbe {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        if (args[0].equals("wait")) {
            if (get(args[1] + "/api/greeting?name=Ready").statusCode() != 200) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try { get(args[1] + "/api/greeting?name=Stopped"); System.exit(1); }
            catch (Exception expected) { return; }
        }
        boolean failed = false;
        HttpResponse<String> api = get(args[1] + "/api/greeting?name=Java");
        failed |= report("quarkus-native-api", api.statusCode() == 200
                        && api.body().trim().equals("{\"message\":\"Native, Java\"}"),
                "Native executableのRESTが期待したJSONを返しました", "Native RESTの応答が期待値と異なります");
        HttpResponse<String> health = get(args[1] + "/q/health/ready");
        String body = health.body().replaceAll("\\s+", "");
        failed |= report("quarkus-native-health", health.statusCode() == 200
                        && body.contains("\"status\":\"UP\"")
                        && body.contains("\"name\":\"native-greeting\""),
                "Native containerのreadinessがUPです", "Native containerのreadinessがUPではありません");
        if (failed) System.exit(1);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static boolean report(String id, boolean pass, String ok, String ng) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id, pass ? ok : ng);
        return !pass;
    }
}
