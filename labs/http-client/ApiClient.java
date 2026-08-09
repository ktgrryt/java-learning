import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ApiClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    public static void main(String[] args) throws InterruptedException {
        String baseUrl = args.length == 0 ? "http://localhost:8080" : args[0];
        ApiClient demo = new ApiClient();
        demo.get(baseUrl + "/api/items/1");
        demo.get(baseUrl + "/api/items/99");
        demo.get(baseUrl + "/api/slow");
    }

    private void get(String url) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(500))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.println(response.statusCode() + " " + response.body());
        } catch (HttpTimeoutException e) {
            System.out.println("TIMEOUT " + request.uri().getPath());
        } catch (IOException e) {
            System.out.println("I/O ERROR " + e.getClass().getSimpleName());
        }
    }
}
