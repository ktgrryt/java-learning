package jq.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.net.URI;
import java.util.Locale;

/**
 * このマシンのブラウザから来たリクエストだけを通す門番。
 *
 * このサーバは受け取ったコードをこのマシンで実行する。つまり届いたリクエストは
 * 中身を問わず任意コード実行になるので、127.0.0.1 だけで待ち受けるだけでは足りない。
 * 次の2つが残る。
 *
 * <ol>
 *   <li><b>他サイトからの横断リクエスト</b> …
 *       学習者が別のタブで開いた悪意あるページのJavaScriptは
 *       {@code http://localhost:8123/api/run} へPOSTを投げられる。リクエストは
 *       学習者のマシンから出ていくので、待ち受けアドレスの制限では防げない。
 *       {@code Content-Type: text/plain} で送れば事前確認（プリフライト）も起きない。
 *       応答は読めないが、コードはもう実行されている。</li>
 *   <li><b>DNSリバインディング</b> …
 *       攻撃者が自分のドメインを 127.0.0.1 に向けると、攻撃ページとこのサーバが
 *       同一オリジンになり、応答（進捗・書きかけのコード）まで読めてしまう。</li>
 * </ol>
 *
 * どちらも {@code Host} と {@code Origin} で見分けられる。この2つはブラウザが必ず
 * 自分で付けるヘッダで、ページ側のJavaScriptからは書き換えられないため、
 * 「このアプリの画面から来たか」の判断材料になる。
 */
final class RequestGuard {

    static final String REJECT_MESSAGE =
            "このアプリは、同じパソコンのブラウザで http://localhost:<ポート>/ を開いた"
            + "画面からのみ操作できます（他のサイトからの操作は受け付けません）。";

    private RequestGuard() {
    }

    /** このリクエストを処理してよいか。 */
    static boolean isAllowed(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();

        // Host は「ブラウザがどの名前で繋いだか」。localhost 以外の名前で届いたなら、
        // その名前を 127.0.0.1 に向けた誰かがいる（DNSリバインディング）
        Authority host = parseHostHeader(headers.getFirst("Host"));
        if (host == null || !isLoopbackName(host.name())) {
            return false;
        }

        String origin = headers.getFirst("Origin");
        if (origin == null) {
            // Origin が無いのは curl や tools/verify-solutions.sh のようなブラウザ以外の
            // クライアント。ブラウザはGET以外に必ず Origin を付けるので、ここを通しても
            // サイト横断リクエストは通らない（そもそも待ち受けは 127.0.0.1 だけなので、
            // ブラウザを経由せずここへ届く相手は、すでにこのマシンでコードを動かせている）
            return true;
        }

        // Origin は Host と<b>完全に一致</b>していなければならない。
        //
        // 「localhost なら通す」ではポートの違う別ページを通してしまう。同じマシンで
        // 動いている別のアプリの画面（他プロジェクトの開発サーバ、ローカルで開いた
        // 何かのツールなど）が http://localhost:3000 から叩いてきた場合まで許すことになり、
        // 実測でも fetch とフォーム送信の両方が通ってしまった。
        //
        // このアプリの画面は自分自身へ相対URL（/api/...）で送るので、Origin と Host は
        // 必ず一致する。SSHトンネル越しでも、別ポートで起動していても一致するため、
        // 厳しくしても正当な使い方は壊れない。
        Authority from = parseOrigin(origin);
        return from != null && from.equals(host);
    }

    /** ホスト名とポートの組。ポートが省略されていたら 80 として扱う。 */
    private record Authority(String name, int port) {
    }

    /** 弾いた理由をコンソールに出す。SSHトンネル経由などで戸惑ったときの手がかり。 */
    static void logRejection(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        System.err.println("拒否: " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + "（Host: " + headers.getFirst("Host")
                + " / Origin: " + headers.getFirst("Origin") + "）"
                + " ― このマシンのブラウザ以外からは操作できません。");
    }

    /** {@code Host} ヘッダ（{@code localhost:8123} や {@code [::1]:8123} の形）を分解する。 */
    private static Authority parseHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String host = hostHeader.trim();
        String name;
        String port;
        if (host.startsWith("[")) {           // IPv6リテラルは [::1]:8123 の形で来る
            int close = host.indexOf(']');
            if (close < 0) {
                return null;
            }
            name = host.substring(1, close);
            String rest = host.substring(close + 1);
            port = rest.startsWith(":") ? rest.substring(1) : "";
        } else {
            int colon = host.indexOf(':');
            name = colon >= 0 ? host.substring(0, colon) : host;
            port = colon >= 0 ? host.substring(colon + 1) : "";
        }
        return authority(name, port.isEmpty() ? 80 : parsePort(port));
    }

    /** {@code Origin} ヘッダ（{@code http://localhost:8123} の形）を分解する。 */
    private static Authority parseOrigin(String origin) {
        URI uri;
        try {
            uri = new URI(origin.trim());
        } catch (Exception e) {
            return null;   // "null"（サンドボックス内のページ）などはここで落ちる
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        int defaultPort;
        if (scheme.equalsIgnoreCase("http")) {
            defaultPort = 80;
        } else if (scheme.equalsIgnoreCase("https")) {
            defaultPort = 443;
        } else {
            return null;   // file:// や拡張機能のスキームは画面のオリジンではない
        }
        String name = uri.getHost();
        if (name == null) {
            return null;
        }
        if (name.startsWith("[") && name.endsWith("]")) {   // URI#getHost は括弧付きで返す
            name = name.substring(1, name.length() - 1);
        }
        return authority(name, uri.getPort() >= 0 ? uri.getPort() : defaultPort);
    }

    private static Authority authority(String name, int port) {
        if (name.isEmpty() || port < 0) {
            return null;
        }
        return new Authority(name.toLowerCase(Locale.ROOT), port);
    }

    private static int parsePort(String port) {
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * ホスト名がこのマシン自身を指すか。
     *
     * 名前解決は絶対にしない。{@code evil.example} を 127.0.0.1 に向けるのが
     * 攻撃そのものなので、解決してから判定すると意味がなくなる。文字列のまま、
     * ループバックだと分かっている書き方だけを許す。
     */
    private static boolean isLoopbackName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String host = name.toLowerCase(Locale.ROOT);
        // localhost.evil.example のような名前を通さないよう、必ず完全一致で見る
        return host.equals("localhost")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")
                || isIpv4Loopback(host);
    }

    /** 127.0.0.0/8 の数値表記か（{@code 127.0.0.1} と、ブラウザが受ける {@code 127.1} も）。 */
    private static boolean isIpv4Loopback(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return parts[0].equals("127");
    }
}
