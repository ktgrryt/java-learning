package jq.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * web/ ディレクトリの静的ファイルを配信する。
 *
 * 学習用のローカルアプリなので凝ったことはしないが、ディレクトリ外への
 * パス脱出（`../` など）は必ず弾く。
 */
public final class StaticHandler implements HttpHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".json", "application/json; charset=utf-8",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon",
            ".png", "image/png",
            ".woff2", "font/woff2");

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String rawPath = exchange.getRequestURI().getPath();
            if (rawPath.equals("/") || rawPath.isEmpty()) {
                rawPath = "/index.html";
            }
            Path target = root.resolve(rawPath.substring(1)).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                send(exchange, 404, "text/plain; charset=utf-8",
                        ("見つかりません: " + rawPath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            byte[] body = Files.readAllBytes(target);
            // 開発中に編集がすぐ反映されるようキャッシュは無効化する
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            send(exchange, 200, contentType(target.getFileName().toString()), body);
        } finally {
            exchange.close();
        }
    }

    private static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
