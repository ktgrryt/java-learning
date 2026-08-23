package jq.web;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub に公開されている版と、いま動いている版を比べる（設定パネルの「このアプリ」）。
 *
 * <p><b>このアプリが外へ通信する唯一の場所である。</b>ほかは全部 127.0.0.1 の中で閉じている
 * （{@link jq.App}）。だから、ここが守る約束を先に書いておく。
 *
 * <ul>
 *   <li><b>取ってくるのは版の文字列だけで、送るものは何もない。</b>進捗・書いたコード・
 *       学習の記録は一切載せない。GitHub 側から見えるのは、リクエストを出したIPと時刻だけ</li>
 *   <li><b>ファイルを書き換えない。</b>知らせるだけで、更新そのものは利用者が `git pull` でやる。
 *       `content/` のJSONは提出コードと一緒にコンパイル・実行されるので（→ docs/guide.md
 *       「コードの実行について」）、取ってきたものでファイルを置き換える作りにすると、
 *       それはそのまま遠隔からのコード実行になる</li>
 *   <li><b>読んだ文字列は {@link #VERSION_LINE} で検証してから通す。</b>画面側は版を
 *       innerHTML に差し込むので、素通しするとXSSになり、あの画面は `/api/run` を
 *       叩ける ―― つまりXSSがそのままローカルのコード実行につながる。
 *       {@code 1.2.3} の形だけを通し、それ以外は「取れなかった」として捨てる</li>
 *   <li><b>失敗しても繰り返さない。</b>GitHub の best practices は、制限中に押し続けると
 *       integration ごと ban しうる・404 の繰り返しは secondary limit を誘発しうる、と
 *       書いている。事故の形は「将来 README の版の行を消したとき、全インストールが
 *       404 を叩き続ける」なので、失敗も {@link #FAIL_TTL_MS} のあいだ覚えて黙る</li>
 * </ul>
 *
 * <p>取得先は README の末尾の {@code version X.Y.Z} である。タグや release ではないのは、
 * どちらもリポジトリに push されていないため（{@code /releases/latest} は404が返る）。
 * README のこの行は {@code tools/check-version.sh} が見張っているので、勝手に形が変わらない。
 * 版の置き場所を増やすときは、あそこの SOURCES とこの {@link #VERSION_LINE} を対で直すこと。
 *
 * <p>通信するかどうかを決めるのは画面側である。ここは {@code GET /api/update} を
 * 呼ばれたときにしか動かないので、設定パネルで切っておけば**接続そのものが起きない**。
 */
public final class UpdateCheck {

    /** 版が書いてあるファイル。API ではなく raw なので、レート制限の枠を消費しない。 */
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/ktgrryt/java-learning/main/README.md";

    /** 更新のしかたを案内するときに見せる場所。 */
    public static final String REPOSITORY_URL = "https://github.com/ktgrryt/java-learning";

    /**
     * 通すのは {@code version 1.2.3} の行だけ。桁数にも上限を付けてあるのは、
     * 数字が延々と並んだ行を版として画面へ流さないため。
     * {@code \s*$} にしないのは、{@code \s} が改行にも当たって次の行末まで伸びるから。
     */
    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^version (\\d{1,4}\\.\\d{1,4}\\.\\d{1,4})[ \\t\\r]*$");

    /** 取れたら1日は聞き直さない。 */
    private static final long OK_TTL_MS = Duration.ofHours(24).toMillis();

    /** 失敗したら6時間は聞き直さない（画面を何度開き直しても叩きに行かせない）。 */
    private static final long FAIL_TTL_MS = Duration.ofHours(6).toMillis();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** README は3KB弱。嘘の Content-Length を書かれても、ここで頭打ちにする。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    // ---- 覚えておくぶん（プロセスの中だけ。ファイルには残さない）--------------
    // 起動しなおせば1回は取りに行くが、それでも「起動ごとに1回」なので十分に少ない。
    // ディスクに置くと、消し忘れの状態ファイルが1つ増えるほうが厄介になる。
    private static String latest;        // 取れた版（取れていなければ null）
    private static String etag;          // 次回 If-None-Match に付ける
    private static long checkedAt;       // 最後に「試した」時刻（成否は問わない）
    private static boolean everChecked;
    private static boolean lastOk;

    private UpdateCheck() {
    }

    /**
     * 設定パネルへ返す形。まだ調べていない・期限が切れていれば、ここで1回だけ取りに行く。
     *
     * <p>{@code synchronized} なのは、画面を素早く開き直したときに同じ取得が並ばないため。
     * 待たされるのはこの endpoint だけで、学習の操作（{@code /api/state}・{@code /api/run}）
     * は別のリクエストなので止まらない。
     */
    public static synchronized Map<String, Object> status() {
        if (isStale()) {
            refresh();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", EnvironmentInfo.APP_VERSION);
        out.put("latest", latest);   // 取れていなければ null
        out.put("updateAvailable", latest != null && isNewer(latest, EnvironmentInfo.APP_VERSION));
        out.put("checked", lastOk);  // false なら「確認できませんでした」を出す
        out.put("repositoryUrl", REPOSITORY_URL);
        return out;
    }

    private static boolean isStale() {
        if (!everChecked) {
            return true;
        }
        long age = System.currentTimeMillis() - checkedAt;
        // 時計が巻き戻ると age が負になる。そのときも「古い」とみなして1回だけ取り直す。
        return age < 0 || age >= (lastOk ? OK_TTL_MS : FAIL_TTL_MS);
    }

    /**
     * 1回だけ取りに行く。どう転んでも例外は投げない ―― 更新の確認が失敗しても、
     * 学習はそのまま続けられなければならない（オフラインで使えることが前提のアプリである）。
     */
    private static void refresh() {
        everChecked = true;
        checkedAt = System.currentTimeMillis();
        // ここで false にしておく。取れたときだけ true に戻す。
        lastOk = false;

        // 使い終わったら閉じる。1日1回のために selector スレッドを常駐させない。
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // NORMAL は HTTPS から HTTP への転送だけ追わない。平文へ落とされない。
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(VERSION_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/plain")
                    // 名乗っておく。GitHub 側から見て何の通信か分かるようにするため。
                    .header("User-Agent", "JavaCafe/" + EnvironmentInfo.APP_VERSION + " (update check)")
                    .GET();
            if (etag != null) {
                // 変わっていなければ 304 が返り、本文が流れない
                request.header("If-None-Match", etag);
            }

            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (status == 304) {
                // 前回と同じ。latest はそのまま使える
                lastOk = latest != null;
                return;
            }
            if (status != 200) {
                // 404（版の行が消えた・リポジトリが動いた）もここ。次は FAIL_TTL_MS まで来ない
                return;
            }

            String body = readCapped(response);
            Matcher m = VERSION_LINE.matcher(body);
            if (!m.find()) {
                // 形が変わった。読めない文字列を画面へ出すより、黙って諦めるほうがよい
                return;
            }
            latest = m.group(1);
            lastOk = true;
            etag = response.headers().firstValue("ETag").orElse(null);

        } catch (Exception e) {
            // 圏外・DNSが引けない・TLSが検証できない・タイムアウト。どれも黙って諦める。
            // 例外の中身は画面へ出さない（利用者にできることが無く、次に出る版の行を
            // 押しのけるだけなので）。
            lastOk = false;
        }
    }

    /** 本文を {@link #MAX_BODY_BYTES} で打ち切って読む。 */
    private static String readCapped(HttpResponse<InputStream> response) throws Exception {
        try (InputStream in = response.body()) {
            return new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code candidate} が {@code current} より新しいか。
     *
     * <p>文字列の大小では測れない（{@code "1.10.0" < "1.9.0"} になる）ので、
     * 3つの数として比べる。どちらかが形を外していたら「新しくない」に倒す ――
     * 判断できないときに更新をうながすと、直せない案内が出たままになる。
     */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    /** {@code "1.2.3"} を {@code [1,2,3]} にする。形が違えば null。 */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] < 0) {
                return null;
            }
        }
        return out;
    }
}
