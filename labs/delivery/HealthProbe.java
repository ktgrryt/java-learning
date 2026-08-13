import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HealthProbe {
    public static void main(String[] args) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(args[0]))
                .timeout(Duration.ofSeconds(2)).GET().build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode() + " " + response.body().trim());
        if (response.statusCode() != 200) System.exit(1);
    }
}
