package jq;

import com.sun.net.httpserver.HttpServer;
import jq.content.ContentLoader;
import jq.progress.ProgressStore;
import jq.web.ApiHandler;
import jq.web.StaticHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Java Quest のローカルサーバ。
 *
 * ユーザーが書いたコードをこのマシンで実行するため、待ち受けは必ず 127.0.0.1 だけにする
 * （同じネットワークの他人がコードを実行できてしまわないように）。
 */
public final class App {

    private static final int DEFAULT_PORT = 8123;
    private static final int PORT_ATTEMPTS = 20;

    public static void main(String[] args) throws IOException {
        Path projectRoot = resolveProjectRoot();
        Path contentDir = projectRoot.resolve("content");
        Path webDir = projectRoot.resolve("web");
        Path progressFile = projectRoot.resolve("progress.json");

        requireDirectory(contentDir, "コンテンツ");
        requireDirectory(webDir, "画面ファイル");

        ContentLoader loader = new ContentLoader(contentDir);
        ProgressStore progress = new ProgressStore(progressFile);

        // 起動時に一度読み込んで、コンテンツの書式ミスをここで気づけるようにする
        int lessonCount = loader.load().totalLessonCount();

        int port = parsePort(args);
        HttpServer server = bind(port, hasFlag(args, "--exact-port"));

        server.createContext("/api", new ApiHandler(loader, progress));
        server.createContext("/", new StaticHandler(webDir));
        // 提出1件でケース数だけ子プロセスを起動する。並行実行しても詰まらない程度に確保する
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort();
        System.out.println();
        System.out.println("  ☕  Java Quest が起動しました");
        System.out.println();
        System.out.println("      " + url);
        System.out.println();
        System.out.println("      レッスン数 : " + lessonCount);
        System.out.println("      進捗の保存先: " + progressFile.toAbsolutePath());
        System.out.println("      終了するには Ctrl+C");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            System.out.println("Java Quest を終了しました。おつかれさまでした。");
        }));
    }

    /**
     * 待ち受けポートを決めてサーバを作る。
     *
     * 人が使うときは、指定ポートが埋まっていたら順に +1 して空きを探すのが親切なので
     * そうしている。ただし「このポートで動いているはず」と決め打ちして繋ぐ相手
     * （tools/verify-solutions.sh）にとっては、黙って別のポートへ移られると
     * 別のサーバに繋ぎに行ってしまう。そのため {@code --exact-port} を付けた場合は
     * ずらさずに失敗させる。
     *
     * @param exact true なら startPort 以外では起動しない
     */
    private static HttpServer bind(int startPort, boolean exact) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        if (exact) {
            try {
                return HttpServer.create(new InetSocketAddress(loopback, startPort), 0);
            } catch (IOException e) {
                throw new IOException("ポート " + startPort + " を使えませんでした"
                        + "（--exact-port が指定されているのでずらしません）", e);
            }
        }
        IOException last = null;
        for (int p = startPort; p < startPort + PORT_ATTEMPTS; p++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(loopback, p), 0);
                if (p != startPort) {
                    // 黙ってずらすと「8123 を開いたのに違うサーバが出た」と混乱するので必ず知らせる
                    System.out.println("  ポート " + startPort + " は使用中だったので "
                            + p + " で起動します。");
                }
                return server;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("ポート " + startPort + " から " + (startPort + PORT_ATTEMPTS - 1)
                + " まで全て使用中でした", last);
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("--port の値が数値ではありません: " + args[i + 1]);
                }
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * content/ と web/ があるディレクトリを探す。
     * 通常はカレントディレクトリだが、build/ の中などから起動された場合も上へ辿る。
     */
    private static Path resolveProjectRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cursor != null; i++) {
            if (Files.isDirectory(cursor.resolve("content")) && Files.isDirectory(cursor.resolve("web"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static void requireDirectory(Path dir, String what) {
        if (!Files.isDirectory(dir)) {
            System.err.println(what + "ディレクトリが見つかりません: " + dir.toAbsolutePath());
            System.err.println("プロジェクトのルート（run.sh がある場所）から起動してください。");
            System.exit(1);
        }
    }
}
