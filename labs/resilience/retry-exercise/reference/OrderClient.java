import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 注文APIを呼ぶclient（模範解答）。
 *
 * <p>要点は4つ。
 *
 * <ul>
 *   <li><b>失敗を種類で分ける。</b>5xxと応答なしは「もう一度なら直るかもしれない」、
 *       4xxは「何度送っても直らない」。同じ扱いにすると、直らない要求で相手を叩き続ける。</li>
 *   <li><b>1回ごとの制限時間と、全体の締め切りは別に持つ。</b>
 *       1回だけ短くしても、再試行を重ねれば全体はいくらでも延びる。</li>
 *   <li><b>待ち時間を空けて再試行する。</b>相手が過負荷のときに即座に送り直すと、
 *       落ちている相手をさらに押す。</li>
 *   <li><b>結果を1行に残す。</b>相関IDと試行回数が無いと、あとから「何が起きたか」を追えない。</li>
 * </ul>
 */
public class OrderClient {

    /** 1回の要求の制限時間。 */
    public static final long REQUEST_TIMEOUT_MILLIS = 500;

    /** 1件の取得ぜんたいの締め切り。これを過ぎたら再試行しない。 */
    public static final long DEADLINE_MILLIS = 1_500;

    /** 再試行を含めた試行回数の上限。 */
    public static final int MAX_ATTEMPTS = 4;

    /** 最初の待ち時間。再試行のたびに倍にする。 */
    private static final long FIRST_BACKOFF_MILLIS = 100;

    private final String baseUrl;
    private final HttpClient http;
    private final List<String> logLines = new ArrayList<>();

    public OrderClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MILLIS))
                .build();
    }

    /**
     * 注文を1件取得する。
     *
     * @throws Exception あきらめたとき（恒久的な失敗、上限到達、締め切り超過）
     */
    public String fetch(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MILLIS))   // 1回ぶんの制限時間
                .GET()
                .build();

        long startedAt = System.nanoTime();
        long backoff = FIRST_BACKOFF_MILLIS;
        int attempts = 0;
        String lastProblem = "";

        while (true) {
            attempts++;
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    log(correlationId, path, "ok", attempts, elapsedMillis(startedAt));
                    return response.body();
                }
                if (status >= 400 && status < 500) {
                    // 何度送っても直らない。ここで再試行するのは相手への嫌がらせにしかならない
                    log(correlationId, path, "failed", attempts, elapsedMillis(startedAt));
                    throw new IllegalStateException(
                            "恒久的な失敗のため中止しました: status=" + status + " path=" + path);
                }
                lastProblem = "status=" + status;
            } catch (java.io.IOException | InterruptedException retryable) {
                // 応答なし・打ち切り。もう一度なら通るかもしれない
                if (retryable instanceof InterruptedException) Thread.currentThread().interrupt();
                lastProblem = retryable.getClass().getSimpleName();
            }

            long elapsed = elapsedMillis(startedAt);
            boolean outOfAttempts = attempts >= MAX_ATTEMPTS;
            // 次の待ち時間を足しても締め切りに間に合うか。間に合わないなら、待つ意味がない
            boolean outOfTime = elapsed + backoff >= DEADLINE_MILLIS;
            if (outOfAttempts || outOfTime) {
                log(correlationId, path, "failed", attempts, elapsed);
                throw new IllegalStateException("あきらめました: " + lastProblem
                        + " attempts=" + attempts + " ms=" + elapsed);
            }
            Thread.sleep(backoff);
            backoff *= 2;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 1件の取得の結果を、次の形で1行だけ残す。
     *
     * <pre>event=fetch cid=&lt;相関ID&gt; path=&lt;パス&gt; outcome=&lt;ok|failed&gt; attempts=&lt;試行回数&gt; ms=&lt;所要ミリ秒&gt;</pre>
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
