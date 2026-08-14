import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

/** runtime-labの固定HTTP検査。学習者は編集しない。 */
public class RuntimeProbe {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** 同時に投げる本数。1つのServletインスタンスを同時に通す。 */
    private static final int CONCURRENT = 20;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        String base = args[1];

        if (args[0].equals("wait")) {
            // 配備前は404。応答さえ返れば中身の判定はverifyへ任せる
            if (get(base + "/orders?name=probe").statusCode() == 404) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                get(base + "/orders?name=probe");
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        boolean failed = false;
        failed |= status(base);
        failed |= sharedState(base);
        failed |= escaping(base);
        failed |= charset(base);
        if (failed) System.exit(1);
    }

    /** 作成・照会・見つからない・使えないメソッドで、状態に合ったコードを返すか。 */
    private static boolean status(String base) throws Exception {
        HttpResponse<String> created = postForm(base + "/orders", "item=" + enc("エスプレッソ"));
        String location = created.headers().firstValue("Location").orElse("");
        HttpResponse<String> missing = get(base + "/orders/999999");

        boolean createdOk = created.statusCode() == 201 && location.matches("/orders/\\d+");
        boolean readBack = false;
        if (createdOk) {
            HttpResponse<String> found = get(base + location);
            readBack = found.statusCode() == 200 && found.body().contains("エスプレッソ");
        }
        HttpResponse<String> notAllowed = delete(base + "/orders/1");

        boolean pass = createdOk && readBack && missing.statusCode() == 404
                && notAllowed.statusCode() == 405;
        return report("servlet-status", pass,
                "POSTが201とLocationを返し、作った注文が200で読め、無いIDは404、DELETEは405になりました",
                "POST=" + created.statusCode() + "（Location: " + shorten(location) + "）"
                        + " / 作成後の照会=" + (readBack ? "ok" : "ng")
                        + " / 無いID=" + missing.statusCode() + "（期待404）"
                        + " / DELETE=" + notAllowed.statusCode() + "（期待405）");
    }

    /**
     * 同時に来た要求のデータが混ざらないか。
     *
     * <p>1つのServletインスタンスへ{@value #CONCURRENT}本を同時に投げ、
     * それぞれ自分が送った名前が返るかを見る。要求ごとの値をインスタンスフィールドへ
     * 置いていると、他の要求の名前が返る。
     */
    private static boolean sharedState(String base) throws Exception {
        CyclicBarrier start = new CyclicBarrier(CONCURRENT);
        List<Thread> threads = new ArrayList<>();
        String[] seen = new String[CONCURRENT];
        for (int i = 0; i < CONCURRENT; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                String name = "user-" + index;
                try {
                    start.await();
                    seen[index] = get(base + "/orders?name=" + name).body();
                } catch (Exception failed) {
                    seen[index] = "error:" + failed.getClass().getSimpleName();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();

        int mixed = 0;
        String example = "";
        for (int i = 0; i < CONCURRENT; i++) {
            String body = seen[i] == null ? "" : seen[i];
            if (!body.contains("user-" + i)) {
                mixed++;
                if (example.isEmpty()) {
                    example = "user-" + i + "が受け取った本文: " + shorten(body);
                }
            }
        }
        return report("servlet-shared-state", mixed == 0,
                CONCURRENT + "本を同時に投げても、それぞれ自分が送った名前が返りました",
                CONCURRENT + "本のうち" + mixed + "本が他の要求の値を受け取りました。" + example);
    }

    /** 受け取った値がHTMLとして解釈されないか。 */
    private static boolean escaping(String base) throws Exception {
        String attack = "<script>alert(1)</script>";
        HttpResponse<String> response = get(base + "/orders?name=" + enc(attack));
        String body = response.body();
        boolean pass = !body.contains("<script>") && body.contains("&lt;script&gt;");
        return report("servlet-escaping", pass,
                "値に混ぜたタグが実体参照へ置き換えられ、HTMLとして解釈されませんでした",
                "本文にタグがそのまま出ています: " + shorten(body));
    }

    /**
     * 応答の日本語が壊れずに届くか（文字コードを指定しているか）。
     *
     * <p>見るのは<b>Servlet自身が書いた日本語</b>（「ようこそ」「さん」）。
     * 送った値の往復ではなく応答の書き出しだけを測るので、
     * クエリ文字列の解釈（コンテナ側の設定）とは切り離せる。
     */
    private static boolean charset(String base) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/orders?name=ascii"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String body = new String(response.body(), StandardCharsets.UTF_8);
        boolean pass = contentType.toLowerCase().contains("charset=utf-8")
                && body.contains("ようこそ") && body.contains("さん");
        return report("servlet-charset", pass,
                "Content-Typeにcharsetを指定し、応答の日本語がそのまま届きました（" + contentType + "）",
                "Content-Type: " + shorten(contentType) + " / 本文: " + shorten(body)
                        + " … 本文を書く前にcharsetまで指定してください");
    }

    // ---- HTTP の道具 --------------------------------------------------------

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> postForm(String url, String form) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String raw) {
        return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** 失敗理由へ入れる実測値。改行やタブは検査結果の書式を壊すので落とす。 */
    private static String shorten(String text) {
        String single = text == null ? "" : text.replaceAll("[\\r\\n\\t]", " ");
        return single.length() <= 120 ? single : single.substring(0, 120) + "...";
    }

    private static boolean report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        return !pass;
    }
}
