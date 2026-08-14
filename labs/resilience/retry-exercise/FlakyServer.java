import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 失敗のしかたを決めてあるlocal server。参照専用。
 *
 * <p>再試行の検査で大事なのは「何回来たか」を<b>受け取った側が数えること</b>。
 * clientの自己申告を信じると、実際には1回しか送っていないのに
 * 「3回試した」と書くだけで通ってしまう。
 *
 * <ul>
 *   <li>{@code /transient} … 最初の2回は503。3回目から200（一時的な失敗）</li>
 *   <li>{@code /permanent} … いつでも400（再試行しても直らない失敗）</li>
 *   <li>{@code /slow} … 4秒待ってから200（打ち切らないと待たされる）</li>
 *   <li>{@code /counts} … ここまでに受け取った回数。検査だけが使う</li>
 * </ul>
 */
public final class FlakyServer {

    private static final int TRANSIENT_FAILURES = 2;
    private static final long SLOW_MILLIS = 4_000;

    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        // 遅い応答を返している間も他の要求を受けられるようにする
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/transient", exchange -> {
            int seen = count("transient");
            if (seen <= TRANSIENT_FAILURES) {
                respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
            } else {
                respond(exchange, 200, "{\"order\":\"A-1001\"}");
            }
        });

        server.createContext("/permanent", exchange -> {
            count("permanent");
            respond(exchange, 400, "{\"error\":\"invalid order id\"}");
        });

        server.createContext("/slow", exchange -> {
            count("slow");
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"order\":\"A-9999\"}");
        });

        server.createContext("/counts", exchange -> respond(exchange, 200,
                "transient=" + seen("transient")
                        + " permanent=" + seen("permanent")
                        + " slow=" + seen("slow")));

        server.start();
        System.out.println("SERVER-READY " + port);
        System.out.flush();
    }

    private static int count(String path) {
        return COUNTS.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
    }

    private static int seen(String path) {
        AtomicInteger counter = COUNTS.get(path);
        return counter == null ? 0 : counter.get();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
