package jq.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

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

    /**
     * gzipで返す下限。
     *
     * これより小さい応答は、圧縮しても縮む量が数百バイトにしかならない（gzipのヘッダと
     * 辞書のぶんで増えることもある）。カフェの購入やヒント1件のような小さな応答は素で返す。
     */
    private static final int GZIP_MIN_BYTES = 1_024;

    private final Path root;

    public StaticHandler(Path root) throws IOException {
        // root自体も実体へ解決し、後続の包含判定をsymlinkを含まない同じ基準で行う。
        this.root = root.toRealPath();
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
            Path target = resolveRegularFileUnder(root, rawPath.substring(1));
            if (target == null) {
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
    static Path resolveRegularFileUnder(Path root, String relative) {
        try {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            // normalize/startsWithだけではroot内のsymlinkが外部を指すケースを防げない。
            // 実体パスへ解決した後でもroot配下にある通常ファイルだけを配信する。
            Path real = candidate.toRealPath();
            return real.startsWith(root)
                    && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) ? real : null;
        } catch (IOException | RuntimeException e) {
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
     *
     * <p>本文が大きければgzipで返す。{@code /api/state} は全カリキュラムの解説とサンプルを
     * 載せるので3MBを超えており（章が増えるほど伸びる）、素で返すとブラウザが受け取り終わるまで
     * 画面が出ない。文字ばかりなので圧縮がよく効く。JDKのhttpserverは自分では圧縮しないので
     * ここで行う。</p>
     */
    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        // 同じURLでも Accept-Encoding で中身（の符号化）が変わることを伝える
        headers.set("Vary", "Accept-Encoding");

        byte[] payload = body;
        if (body.length >= GZIP_MIN_BYTES && isCompressible(contentType) && acceptsGzip(exchange)) {
            byte[] compressed = gzip(body);
            // すでに圧縮済みの中身では逆に増えることがある。増えたら素のまま返す
            if (compressed.length < body.length) {
                payload = compressed;
                headers.set("Content-Encoding", "gzip");
            }
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    /**
     * 圧縮して意味のある型か。
     *
     * 画像やフォント（png / woff2）はそれ自体が圧縮済みなので、かけても縮まずCPUだけ使う。
     * 縮むのは文字の型（html / css / js / json / svg）だけ。
     */
    private static boolean isCompressible(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.startsWith("application/json")
                || type.startsWith("image/svg+xml");
    }

    /**
     * 相手がgzipを受け取れるか。
     *
     * ブラウザは必ず付けてくる。{@code curl} や {@code tools/verify-solutions.sh} のような
     * ブラウザ以外は付けないので、素のまま返る（検査側で展開する必要はない）。
     * {@code gzip;q=0} は「使わないでほしい」という意思表示なので断る。
     */
    private static boolean acceptsGzip(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Accept-Encoding");
        if (values == null) {
            return false;
        }
        for (String value : values) {
            for (String entry : value.split(",")) {
                String token = entry.trim();
                int semicolon = token.indexOf(';');
                String name = (semicolon < 0 ? token : token.substring(0, semicolon)).trim();
                if (name.equalsIgnoreCase("gzip")) {
                    return semicolon < 0 || quality(token.substring(semicolon + 1)) > 0.0;
                }
            }
        }
        return false;
    }

    /** {@code ;q=0.5} の重み。書いていない・読めない場合は1（受け取れる）とみなす。 */
    private static double quality(String parameters) {
        for (String parameter : parameters.split(";")) {
            String p = parameter.trim();
            if (p.regionMatches(true, 0, "q=", 0, 2)) {
                try {
                    return Double.parseDouble(p.substring(2).trim());
                } catch (NumberFormatException e) {
                    return 1.0;
                }
            }
        }
        return 1.0;
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, body.length / 8));
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(body);
        }
        return buffer.toByteArray();
    }
}
