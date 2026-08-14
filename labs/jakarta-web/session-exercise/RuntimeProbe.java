import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * runtime-labの固定HTTP検査。学習者は編集しない。
 *
 * <p>ブラウザごとの分離を測るので、Cookieの入れ物（{@link CookieManager}）を分けた
 * 別々のHTTP clientを使う。同じclientを使い回すと「別の利用者」を作れない。
 *
 * <p>いま持っているセッションIDは、入れ物から直接読む。{@code CookieHandler}が付けたCookieは
 * 要求ヘッダには現れないので、応答からは読めない。
 */
public class RuntimeProbe {

    /** Cookieの入れ物を持つ「1つのブラウザ」。 */
    private record Browser(HttpClient client, CookieManager cookies) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and base URL are required");
        String base = args[1];

        if (args[0].equals("wait")) {
            if (get(browser(), base + "/cart").statusCode() == 404) System.exit(1);
            return;
        }
        if (args[0].equals("stopped")) {
            try {
                get(browser(), base + "/cart");
                System.exit(1);
            } catch (Exception expected) {
                return;
            }
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("unknown mode");

        Browser alice = browser();
        Browser bob = browser();
        boolean failed = false;

        // ── 1. 同じブラウザなら、かごが次の要求へ残るか ──────────────────────
        post(alice, base + "/cart?item=espresso");
        post(alice, base + "/cart?item=cookie");
        String aliceCart = post(alice, base + "/cart?item=milk").body();
        boolean continuity = aliceCart.contains("espresso") && aliceCart.contains("cookie")
                && aliceCart.contains("milk");
        failed |= report("session-continuity", continuity,
                "同じブラウザからの3回の追加が、次の要求でも残っていました（" + shorten(aliceCart) + "）",
                "3回追加したのに、かごに残っていません（" + shorten(aliceCart) + "）");

        // ── 2. 別のブラウザから、他人のかごが見えないか ──────────────────────
        String bobFirst = get(bob, base + "/cart").body();
        post(bob, base + "/cart?item=latte");
        String bobCart = get(bob, base + "/cart").body();
        String aliceAgain = get(alice, base + "/cart").body();
        boolean isolated = !bobFirst.contains("espresso") && bobFirst.contains("cart=[]")
                && bobCart.contains("latte") && !bobCart.contains("espresso")
                && aliceAgain.contains("espresso") && !aliceAgain.contains("latte");
        failed |= report("session-isolation", isolated,
                "別のブラウザには自分のかごだけが見えました（他人の品物は混ざりません）",
                "他のブラウザのかごが見えています。2人目の最初=" + shorten(bobFirst)
                        + " / 2人目=" + shorten(bobCart) + " / 1人目=" + shorten(aliceAgain));

        // ── 3. ログインでセッションIDを作り直しているか ───────────────────────
        String oldId = sessionId(alice, base);
        HttpResponse<String> login = post(alice, base + "/cart/login?user=aki");
        String newId = sessionId(alice, base);
        // ログイン前のIDだけを提示する第三者。作り直していれば、ログイン後の人にはなれない
        String hijacked = oldId.isEmpty() ? "" : withCookie(base + "/cart", "JSESSIONID=" + oldId);
        boolean rotated = !oldId.isEmpty() && !newId.isEmpty() && !newId.equals(oldId)
                && !hijacked.contains("user=aki");
        String detail = oldId.isEmpty() && newId.isEmpty()
                ? "JSESSIONIDが1度も発行されていません（セッションを使っていません）"
                : "ログイン前のID=" + shorten(oldId) + " / ログイン後のID=" + shorten(newId)
                        + " / 前のIDで入った結果=" + shorten(hijacked);
        failed |= report("session-fixation", rotated,
                "ログインでセッションIDが変わり、ログイン前のIDではログイン後の状態に入れませんでした",
                detail + " … 利用者ごとの状態をセッションへ持ち、"
                        + "ログインで changeSessionId() を呼んでIDを作り直してください");

        // ── 4. Cookieに安全な属性が付いているか ─────────────────────────────
        String visitor = setCookie(login, "visitor");
        String lower = visitor.toLowerCase();
        boolean secured = !visitor.isEmpty() && lower.contains("httponly")
                && lower.contains("samesite");
        failed |= report("cookie-attributes", secured,
                "visitor CookieにHttpOnlyとSameSiteが付きました（" + shorten(visitor) + "）",
                "visitor Cookieの属性が足りません（" + shorten(visitor.isEmpty() ? "無し" : visitor)
                        + "）。HttpOnlyでJavaScriptから読めなくし、SameSiteで他サイトからの送信を止めてください");

        if (failed) System.exit(1);
    }

    // ---- HTTP の道具 --------------------------------------------------------

    private static Browser browser() {
        CookieManager cookies = new CookieManager();
        return new Browser(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(cookies)
                .build(), cookies);
    }

    private static HttpResponse.BodyHandler<String> body() {
        return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> get(Browser browser, String url) throws Exception {
        return browser.client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build(), body());
    }

    private static HttpResponse<String> post(Browser browser, String url) throws Exception {
        return browser.client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), body());
    }

    /** Cookieの入れ物を持たない要求。指定したCookieだけを送る。 */
    private static String withCookie(String url, String cookie) throws Exception {
        HttpClient bare = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return bare.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", cookie).GET().build(), body()).body();
    }

    /** このブラウザがいま持っているセッションID。持っていなければ空。 */
    private static String sessionId(Browser browser, String base) {
        for (HttpCookie cookie : browser.cookies().getCookieStore().get(URI.create(base))) {
            if (cookie.getName().equalsIgnoreCase("JSESSIONID")) {
                return cookie.getValue();
            }
        }
        return "";
    }

    /** 指定した名前のSet-Cookieヘッダ全体（属性つき）。 */
    private static String setCookie(HttpResponse<String> response, String name) {
        List<String> headers = response.headers().allValues("Set-Cookie");
        for (String header : headers) {
            if (header.regionMatches(true, 0, name + "=", 0, name.length() + 1)) {
                return header;
            }
        }
        return "";
    }

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
