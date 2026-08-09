import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class ApiServer {
    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/api/items/1", exchange ->
                send(exchange, 200, "{\"id\":1,\"name\":\"Java\"}"));
        server.createContext("/api/items", exchange ->
                send(exchange, 404, "{\"error\":\"not found\"}"));
        server.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                send(exchange, 200, "{\"status\":\"late\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("listening on http://localhost:" + port);
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
