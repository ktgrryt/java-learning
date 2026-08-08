import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学習用の疑似サーブレットコンテナ。
 *
 * <p>本物のアプリサーバ（Tomcat / WildFly など）は、ネットワークのポートを開いて
 * ブラウザからのHTTPリクエストを待ち受ける。この教材では代わりに
 * <b>標準入力の1行を1リクエストとして扱い</b>、結果を標準出力に出す。
 * こうすると「入力を変えて出力を確かめる」という形で採点できる。
 *
 * <h2>使い方</h2>
 * <pre>
 * MiniWeb.app()
 *        .register(HelloServlet.class)
 *        .serve();
 * </pre>
 *
 * <h2>リクエストの書き方（標準入力）</h2>
 * <pre>
 * GET /hello                     … パスだけ
 * GET /hello?name=Yamada         … クエリ文字列つき
 * POST /login?id=a&amp;pw=b         … POSTのパラメータもクエリ文字列の形で書く
 * GET /cart?add=apple  alice     … 3つめは「誰のセッションか」（省略すると共通のセッション）
 * </pre>
 *
 * <h2>出力の形</h2>
 * <pre>
 * 200 OK                         … ステータス行（必ず出る）
 * Hello, Yamada!                 … 本文（あれば）
 * </pre>
 *
 * <h2>本物とちがうところ</h2>
 * <ul>
 *   <li>ネットワークを使わない（ポートを開かない）</li>
 *   <li>{@code @WebServlet} の走査をしないので、{@code register(...)} で明示的に登録する</li>
 *   <li>リクエストは1つずつ順番に処理する（並行実行しない）</li>
 *   <li>セッションはメモリ上のMap。プロセスが終われば消える</li>
 * </ul>
 */
public final class MiniWeb {

    /** ステータスコードに対応する短い説明。 */
    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(204, "No Content"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(409, "Conflict"),
            Map.entry(500, "Internal Server Error"));

    private final Map<String, HttpServlet> servlets = new LinkedHashMap<>();
    private final List<FilterEntry> filters = new ArrayList<>();
    private final MiniContext context = new MiniContext();
    /** セッション名 → セッション本体。名前は「どのブラウザか」に相当する。 */
    private final Map<String, MiniSession> sessions = new LinkedHashMap<>();
    /** セッション名 → そのブラウザが預かっているCookie。 */
    private final Map<String, Map<String, String>> browserCookies = new LinkedHashMap<>();

    private boolean echoRequests;
    private boolean showCookies;
    private boolean showHeaders;
    private boolean started;

    private MiniWeb() {
    }

    /** 疑似コンテナを作る。 */
    public static MiniWeb app() {
        return new MiniWeb();
    }

    // ------------------------------------------------------------- 登録

    /**
     * サーブレットまたはフィルタを登録する。
     *
     * パスは {@code @WebServlet} / {@code @WebFilter} から読む
     * （本物のアプリサーバが起動時にやっている走査の代わり）。
     */
    public MiniWeb register(Class<?> type) {
        if (HttpServlet.class.isAssignableFrom(type)) {
            WebServlet mapping = type.getAnnotation(WebServlet.class);
            if (mapping == null) {
                throw new IllegalStateException(type.getSimpleName()
                        + " に @WebServlet が付いていません。@WebServlet(\"/パス\") を付けてください。");
            }
            return register(patternOf(mapping.value(), mapping.urlPatterns(), type), type);
        }
        if (Filter.class.isAssignableFrom(type)) {
            WebFilter mapping = type.getAnnotation(WebFilter.class);
            if (mapping == null) {
                throw new IllegalStateException(type.getSimpleName()
                        + " に @WebFilter が付いていません。@WebFilter(\"/*\") を付けてください。");
            }
            String pattern = patternOf(mapping.value(), mapping.urlPatterns(), type);
            filters.add(new FilterEntry(pattern, (Filter) instantiate(type)));
            return this;
        }
        throw new IllegalStateException(type.getSimpleName()
                + " は HttpServlet を継承していないし Filter も実装していません。");
    }

    /** パスを明示して登録する（{@code @WebServlet} を書かない場合）。 */
    public MiniWeb register(String path, Class<?> type) {
        HttpServlet servlet = (HttpServlet) instantiate(type);
        servlet.setServletContextForContainer(context);
        // 本物と同じで、インスタンスは1つだけ作って全リクエストで使い回す
        servlets.put(path, servlet);
        return this;
    }

    /** アプリケーションスコープの初期化パラメータ（web.xml の context-param に相当）。 */
    public MiniWeb initParameter(String name, String value) {
        context.initParameters.put(name, value);
        return this;
    }

    // --------------------------------------------------- 出力を増やす指定

    /** 各レスポンスの前に、処理したリクエストを {@code > GET /hello} の形で出す。 */
    public MiniWeb echoRequests() {
        this.echoRequests = true;
        return this;
    }

    /** レスポンスに付いたCookieを {@code + Cookie: name=value} の形で出す。 */
    public MiniWeb showCookies() {
        this.showCookies = true;
        return this;
    }

    /** レスポンスヘッダを {@code + name: value} の形で出す。 */
    public MiniWeb showHeaders() {
        this.showHeaders = true;
        return this;
    }

    // ------------------------------------------------------------- 実行

    /** 標準入力の各行をリクエストとして処理する。 */
    public void serve() {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("標準入力を読めません", e);
        }
        serve(lines.toArray(new String[0]));
    }

    /** 指定したリクエストを順に処理する（標準入力を使わずに試したいとき）。 */
    public void serve(String... requestLines) {
        startUp();
        for (String line : requestLines) {
            if (line != null && !line.isBlank()) {
                handle(line.strip());
            }
        }
        shutDown();
    }

    // -------------------------------------------------------- 内部の処理

    /** 各サーブレット・フィルタの init() を1回だけ呼ぶ。 */
    private void startUp() {
        if (started) {
            return;
        }
        started = true;
        try {
            for (FilterEntry f : filters) {
                f.filter.init();
            }
            for (HttpServlet s : servlets.values()) {
                s.init();
            }
        } catch (ServletException e) {
            throw new IllegalStateException("初期化に失敗しました: " + e.getMessage(), e);
        }
    }

    private void shutDown() {
        for (HttpServlet s : servlets.values()) {
            s.destroy();
        }
        for (FilterEntry f : filters) {
            f.filter.destroy();
        }
    }

    private void handle(String requestLine) {
        // 形式: METHOD PATH[?query] [セッション名]
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 2) {
            System.out.println("400 Bad Request");
            System.out.println("! リクエストの形が違います: " + requestLine);
            return;
        }
        String method = parts[0].toUpperCase();
        String target = parts[1];
        String sessionName = parts.length > 2 ? parts[2] : "default";

        String path = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        if (echoRequests) {
            System.out.println("> " + method + " " + target);
        }

        MiniRequest req = new MiniRequest(method, path, query, sessionName);
        MiniResponse res = new MiniResponse();

        HttpServlet servlet = servlets.get(path);
        try {
            if (servlet == null && matchingFilters(path).isEmpty()) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND, path + " に対応するサーブレットがありません");
            } else {
                // フィルタ → … → サーブレット の順に数珠つなぎで呼ぶ
                new MiniChain(matchingFilters(path), servlet).doFilter(req, res);
            }
        } catch (Exception e) {
            // 本物のコンテナも、サーブレットが投げた例外は500にして返す。
            // 原因を追えるようにスタックトレースは標準エラーへ出す（採点は標準出力だけを見る）
            res.reset();
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            res.errorMessage = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            e.printStackTrace();
        }
        print(req, res);

        // isNew() は「作られたそのリクエストの間だけ true」。次のリクエストからは false にする
        MiniSession used = sessions.get(req.sessionName);
        if (used != null) {
            used.brandNew = false;
        }
    }

    private List<Filter> matchingFilters(String path) {
        List<Filter> matched = new ArrayList<>();
        for (FilterEntry f : filters) {
            if (matches(f.pattern, path)) {
                matched.add(f.filter);
            }
        }
        return matched;
    }

    /** {@code /admin/*} のような末尾ワイルドカードと、完全一致だけを見る。 */
    private static boolean matches(String pattern, String path) {
        if (pattern.equals("/*")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }

    private void print(MiniRequest req, MiniResponse res) {
        int status = res.getStatus();
        System.out.println(status + " " + REASONS.getOrDefault(status, ""));

        if (res.redirectTo != null) {
            System.out.println("-> " + res.redirectTo);
        }
        if (res.errorMessage != null) {
            System.out.println("! " + res.errorMessage);
        }
        if (showHeaders) {
            res.headers.forEach((k, v) -> System.out.println("+ " + k + ": " + v));
        }
        if (showCookies) {
            for (Cookie c : res.cookies) {
                System.out.println("+ Cookie: " + c.getName() + "=" + c.getValue()
                        + (c.getMaxAge() >= 0 ? " (maxAge=" + c.getMaxAge() + ")" : ""));
            }
        }
        String body = res.body();
        if (!body.isEmpty()) {
            System.out.print(body);
            if (!body.endsWith("\n")) {
                System.out.println();
            }
        }

        // ブラウザ役として、返ってきたCookieを預かって次のリクエストで送り返す
        Map<String, String> jar = browserCookies.computeIfAbsent(
                req.sessionName, k -> new LinkedHashMap<>());
        for (Cookie c : res.cookies) {
            if (c.getMaxAge() == 0) {
                jar.remove(c.getName());
            } else {
                jar.put(c.getName(), c.getValue());
            }
        }
    }

    private static String patternOf(String value, String[] urlPatterns, Class<?> type) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (urlPatterns != null && urlPatterns.length > 0 && !urlPatterns[0].isBlank()) {
            return urlPatterns[0];
        }
        throw new IllegalStateException(type.getSimpleName()
                + " のURLパターンが空です。@WebServlet(\"/パス\") のように書いてください。");
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName()
                    + " を作れません。引数なしのコンストラクタが必要です。", e);
        }
    }

    // ------------------------------------------------------- 内部クラス

    private record FilterEntry(String pattern, Filter filter) {
    }

    /** フィルタを順に呼び、最後にサーブレットへ届ける。 */
    private final class MiniChain implements FilterChain {
        private final List<Filter> chain;
        private final HttpServlet servlet;
        private int index;

        MiniChain(List<Filter> chain, HttpServlet servlet) {
            this.chain = chain;
            this.servlet = servlet;
        }

        @Override
        public void doFilter(HttpServletRequest req, HttpServletResponse res)
                throws ServletException, IOException {
            if (index < chain.size()) {
                Filter next = chain.get(index++);
                next.doFilter(req, res, this);
                return;
            }
            if (servlet == null) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            servlet.service(req, res);
        }
    }

    private final class MiniRequest implements HttpServletRequest {
        private final String method;
        private final String path;
        private final String query;
        private final String sessionName;
        private final Map<String, List<String>> params = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        MiniRequest(String method, String path, String query, String sessionName) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.sessionName = sessionName;
            if (query != null && !query.isEmpty()) {
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    String[] kv = pair.split("=", 2);
                    params.computeIfAbsent(decode(kv[0]), k -> new ArrayList<>())
                            .add(kv.length > 1 ? decode(kv[1]) : "");
                }
            }
        }

        /**
         * URLエンコードを戻す（{@code %XX} と {@code +}）。
         *
         * {@code %XX} は<b>1バイト</b>を表すので、いったんバイト列に組み直してから
         * まとめてUTF-8として解釈する。1つずつ文字にしていくと、日本語のように
         * 複数バイトで1文字になるものが文字化けする（{@code 山田} が {@code å±±ç°} になる）。
         */
        private static String decode(String s) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+') {
                    bytes.write(' ');
                } else if (c == '%' && i + 2 < s.length()) {
                    try {
                        bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    } catch (NumberFormatException ignored) {
                        bytes.write('%');   // %XX の形でなければ、ただの記号として扱う
                    }
                } else {
                    bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                }
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public String getRequestURI() {
            return path;
        }

        @Override
        public String getQueryString() {
            return query;
        }

        @Override
        public String getParameter(String name) {
            List<String> values = params.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public String[] getParameterValues(String name) {
            List<String> values = params.get(name);
            return values == null ? null : values.toArray(new String[0]);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        @Override
        public HttpSession getSession(boolean create) {
            MiniSession existing = sessions.get(sessionName);
            if (existing != null) {
                return existing;
            }
            if (!create) {
                return null;
            }
            MiniSession created = new MiniSession(sessionName);
            sessions.put(sessionName, created);
            return created;
        }

        @Override
        public Cookie[] getCookies() {
            Map<String, String> jar = browserCookies.get(sessionName);
            if (jar == null || jar.isEmpty()) {
                return null;   // 本物と同じで、無いときは空配列ではなく null
            }
            List<Cookie> list = new ArrayList<>();
            jar.forEach((k, v) -> list.add(new Cookie(k, v)));
            return list.toArray(new Cookie[0]);
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String target) {
            return new MiniDispatcher(target);
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }

        @Override
        public void setCharacterEncoding(String encoding) {
            // この教材は最初からUTF-8で扱うので何もしない。
            // 本物ではPOSTの文字化けを防ぐため、パラメータを読む前に呼ぶ必要がある
        }
    }

    private final class MiniResponse implements HttpServletResponse {
        private int status = SC_OK;
        private StringWriter buffer = new StringWriter();
        private PrintWriter writer = new PrintWriter(buffer);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<Cookie> cookies = new ArrayList<>();
        private String redirectTo;
        private String errorMessage;

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        @Override
        public void setContentType(String type) {
            headers.put("Content-Type", type);
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public void sendError(int sc) {
            sendError(sc, null);
        }

        @Override
        public void sendError(int sc, String message) {
            reset();
            this.status = sc;
            this.errorMessage = message;
        }

        @Override
        public void sendRedirect(String location) {
            reset();
            this.status = SC_FOUND;
            this.redirectTo = location;
        }

        @Override
        public void addCookie(Cookie cookie) {
            cookies.add(cookie);
        }

        /** 本文を捨てる。sendError / sendRedirect のあとは本文を返さないため。 */
        void reset() {
            buffer = new StringWriter();
            writer = new PrintWriter(buffer);
            redirectTo = null;
            errorMessage = null;
        }

        String body() {
            writer.flush();
            return buffer.toString();
        }
    }

    private final class MiniDispatcher implements RequestDispatcher {
        private final String target;

        MiniDispatcher(String target) {
            this.target = target;
        }

        @Override
        public void forward(HttpServletRequest req, HttpServletResponse res)
                throws ServletException, IOException {
            HttpServlet servlet = servlets.get(target);
            if (servlet == null) {
                // ビュー（JSP）へのforwardは、この教材では登録済みサーブレットだけを対象にする
                res.sendError(HttpServletResponse.SC_NOT_FOUND,
                        target + " に転送できません（register していないパスです）");
                return;
            }
            servlet.service(req, res);
        }

        @Override
        public void include(HttpServletRequest req, HttpServletResponse res)
                throws ServletException, IOException {
            HttpServlet servlet = servlets.get(target);
            if (servlet != null) {
                servlet.service(req, res);
            }
        }
    }

    private final class MiniSession implements HttpSession {
        private final String id;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean brandNew = true;
        private boolean valid = true;

        MiniSession(String id) {
            this.id = id;
        }

        private void checkValid() {
            if (!valid) {
                throw new IllegalStateException("このセッションは invalidate() 済みです");
            }
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isNew() {
            checkValid();
            return brandNew;
        }

        @Override
        public void setAttribute(String name, Object value) {
            checkValid();
            attributes.put(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            checkValid();
            return attributes.get(name);
        }

        @Override
        public void removeAttribute(String name) {
            checkValid();
            attributes.remove(name);
        }

        @Override
        public void invalidate() {
            checkValid();
            attributes.clear();
            valid = false;
            // 捨てたセッションを残しておくと、次に getSession() したときに
            // 無効なものを返してしまう。本物と同じく、次は新しいセッションが作られるようにする
            sessions.remove(id, this);
        }
    }

    private static final class MiniContext implements ServletContext {
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Map<String, String> initParameters = new LinkedHashMap<>();

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }

        @Override
        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        @Override
        public void log(String message) {
            System.out.println("[LOG] " + message);
        }
    }
}
