package jq.web;

import com.sun.net.httpserver.Headers;
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
            // 画面そのものを別の名前（DNSリバインディング）で開かせない。
            // ここを通すと、その画面のJavaScriptが同一オリジンとして /api を叩けてしまう
            if (!RequestGuard.isAllowed(exchange)) {
                RequestGuard.logRejection(exchange);
                send(exchange, 403, "text/plain; charset=utf-8",
                        RequestGuard.REJECT_MESSAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }

            String rawPath = exchange.getRequestURI().getPath();
            if (rawPath.equals("/") || rawPath.isEmpty()) {
                rawPath = "/index.html";
            }
            Path target = resolveUnder(root, rawPath.substring(1));
            if (target == null || !target.startsWith(root) || !Files.isRegularFile(target)) {
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

    /**
     * root の下のファイルとして解決する。パスとして扱えない要求なら null。
     *
     * URLのパスはデコードされて渡ってくるので、{@code /%00} のように
     * ファイル名に使えない文字が混ざることがある（{@code Path#resolve} が
     * InvalidPathException を投げる）。投げっぱなしにすると、JDKのhttpserverは
     * 既定のログレベルでは何も出さずに接続を切るため、応答も痕跡も残らない。
     * 存在しないのと同じ扱いにして 404 で答える。
     */
    private static Path resolveUnder(Path root, String relative) {
        try {
            return root.resolve(relative).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * 応答を1本返す。/api も含めた全レスポンスがここを通る。
     *
     * ついでに防御用のヘッダを付ける。中身を読まれることはない（同一生成元ポリシー）が、
     * 外部のページが {@code <iframe src="http://localhost:8123">} でこの画面を埋め込むことは
     * できてしまう。埋め込めると、透明にして重ねた上から「進捗をリセット」を押させる
     * （クリックジャッキング）ことや、このアプリが動いているかを外から調べることができる。
     * {@code X-Frame-Options} と {@code frame-ancestors} でどちらも塞ぐ。
     *
     * CSPは万一この画面にスクリプトを差し込まれたときの保険。画面は自分のJSファイルしか
     * 読まないので {@code 'self'} で足りる（インラインの style 属性を1箇所だけ使っているため
     * style-src には unsafe-inline が要る。favicon はSVGのdata:URLなので img-src に data: が要る）。
     */
    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
