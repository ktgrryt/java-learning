import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ContainerApp {
    public static void main(String[] args) throws Exception {
        AtomicBoolean ready = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/health/live", exchange -> reply(exchange, 200, "UP\n"));
        server.createContext("/health/ready", exchange ->
                reply(exchange, ready.get() ? 200 : 503, ready.get() ? "READY\n" : "DRAINING\n"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ready.set(false);
            server.stop((int) Duration.ofSeconds(5).toSeconds());
        }));
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}

