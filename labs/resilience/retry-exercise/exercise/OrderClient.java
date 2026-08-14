import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 注文APIを呼ぶclient。
 *
 * <p>いまの実装は「1回送って、返ってきた本文をそのまま返す」だけ。相手が落ちていても、
 * 応答が返らなくても、恒久的な失敗でも同じ扱いになる。次の4つを満たすように直す。
 *
 * <ol>
 *   <li>一時的な失敗（5xxや応答なし）は再試行する。ただし{@link #MAX_ATTEMPTS}回まで</li>
 *   <li>恒久的な失敗（4xx）は再試行しない。何度送っても直らないので、相手の負荷を増やすだけ</li>
 *   <li>1回の要求は{@link #REQUEST_TIMEOUT_MILLIS}で打ち切り、
 *       全体は{@link #DEADLINE_MILLIS}を超えたらあきらめる</li>
 *   <li>結果を1行の構造化ログに残す（{@link #logLines()}）</li>
 * </ol>
 *
 * <p>クラス名・メソッドの名前・引数・戻り値・定数は採点の足場が使うので変えないこと。
 */
public class OrderClient {

    /** 1回の要求の制限時間。 */
    public static final long REQUEST_TIMEOUT_MILLIS = 500;

    /** 1件の取得ぜんたいの締め切り。これを過ぎたら再試行しない。 */
    public static final long DEADLINE_MILLIS = 1_500;

    /** 再試行を含めた試行回数の上限。 */
    public static final int MAX_ATTEMPTS = 4;

    private final String baseUrl;
    private final HttpClient http;
    private final List<String> logLines = new ArrayList<>();

    public OrderClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newHttpClient();
    }

    /**
     * 注文を1件取得する。
     *
     * @param path          {@code /transient} のような取得先
     * @param correlationId この取得を追いかけるためのID。ログへ必ず残す
     * @return 取得できた本文
     * @throws Exception あきらめたとき（恒久的な失敗、上限到達、締め切り超過）
     */
    public String fetch(String path, String correlationId) throws Exception {
        // TODO: 試行回数と締め切りを見ながら、一時的な失敗だけ再試行する
        // TODO: 1回の要求に制限時間を付ける
        // TODO: 結果を1行のログに残す（下のlog(...)を使う）
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * 1件の取得の結果を、次の形で1行だけ残す。
     *
     * <pre>event=fetch cid=&lt;相関ID&gt; path=&lt;パス&gt; outcome=&lt;ok|failed&gt; attempts=&lt;試行回数&gt; ms=&lt;所要ミリ秒&gt;</pre>
     *
     * <p>{@code attempts}は<b>実際に送った回数</b>。受け取った側も数えているので、合っていないと分かる。
     */
    protected void log(String correlationId, String path, String outcome, int attempts,
                       long elapsedMillis) {
        logLines.add("event=fetch cid=" + correlationId + " path=" + path
                + " outcome=" + outcome + " attempts=" + attempts + " ms=" + elapsedMillis);
    }

    /** 残したログを、書いた順に返す。 */
    public List<String> logLines() {
        return List.copyOf(logLines);
    }
}
