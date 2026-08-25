package jq;

import com.sun.net.httpserver.HttpServer;
import jq.content.ContentLoader;
import jq.progress.ProgressLock;
import jq.progress.ProgressStore;
import jq.web.ApiHandler;
import jq.web.EnvironmentInfo;
import jq.web.StaticHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Java Café のローカルサーバ。
 *
 * ユーザーが書いたコードをこのマシンで実行するため、待ち受けは必ず 127.0.0.1 だけにする
 * （同じネットワークの他人がコードを実行できてしまわないように）。
 */
public final class App {

    private static final int DEFAULT_PORT = 8123;
    private static final int PORT_ATTEMPTS = 20;
    /** リクエスト処理スレッド数。実行の上限（{@link ApiHandler#MAX_CONCURRENT_RUNS}）より十分多くする。 */
    private static final int THREAD_POOL_SIZE = 16;

    public static void main(String[] args) throws IOException {
        Path projectRoot = resolveProjectRoot();
        Path contentDir = projectRoot.resolve("content");
        Path webDir = projectRoot.resolve("web");
        Path progressFile = projectRoot.resolve("progress.json");

        requireDirectory(contentDir, "コンテンツ");
        requireDirectory(webDir, "画面ファイル");

        // 進捗を読む前に錠を取る。同じ progress.json を見るサーバが2つ動くと、
        // 後から書いた側の写しが勝って、もう一方でやったぶんが黙って消える
        // （保存はファイル全体の書き直しなので、混ぜ合わせようがない）。
        ProgressLock lock = acquireProgressLock(progressFile);

        ContentLoader loader = new ContentLoader(contentDir);
        ProgressStore progress = openProgress(progressFile, lock);

        // 起動時に一度読み込んで、コンテンツの書式ミスをここで気づけるようにする
        int lessonCount = loader.load().totalLessonCount();

        int port = parsePort(args);
        HttpServer server = bind(port, hasFlag(args, "--exact-port"));

        server.createContext("/api", new ApiHandler(loader, progress));
        server.createContext("/", new StaticHandler(webDir));
        // このプールは画面ファイルの配信もコードの実行も一緒に処理する（httpserver は
        // コンテキストごとにプールを分けられない）。実行は1件で最大5秒×ケース数かかるので、
        // 実行だけでプールを埋めると画面が丸ごと固まる。そこで
        //   ・プールは余裕をもって確保する
        //   ・同時に走らせる実行は ApiHandler 側で数を絞る（MAX_CONCURRENT_RUNS）
        // の2段構えにして、実行が混み合っていても画面の表示と /api/state は必ず通す。
        server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort();
        System.out.println();
        System.out.println("  ☕  Java Café v" + EnvironmentInfo.APP_VERSION + " が起動しました");
        System.out.println();
        System.out.println("      " + url);
        System.out.println();
        System.out.println("      レッスン数 : " + lessonCount);
        System.out.println("      進捗の保存先: " + progressFile.toAbsolutePath());
        System.out.println("      終了するには Ctrl+C");
        System.out.println();

        // ブラウザを開くのはここだけにする。指定ポートが埋まっていると bind が +1 して
        // ずれるので、呼ぶ側（run.sh）が同じポートを決め打ちで開くと、すでに動いている
        // 別のサーバを開いてしまう（8123 にアプリが残っていると必ずこうなる）。
        // 実際に使ったポートを知っているのはここだけなので、開く役もここに置く。
        if (hasFlag(args, "--open")) {
            openBrowser(url);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            // 進捗の書き出しはまとめて遅らせているので、終了前に必ず1回吐き出す
            progress.flushNow();
            // 錠を手放すのは書き出したあと。先に手放すと、書いている途中に次のプロセスが入れる
            lock.close();
            System.out.println("Java Café を終了しました。おつかれさまでした。");
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

    /**
     * 進捗ファイルの錠を取る。取れなければ案内を出して終了する。
     *
     * <p>ここで止めるのは親切のためではなく、続けると<b>進捗が消える</b>ためである。
     * サーバは進捗を丸ごとメモリに持ち、保存はファイル全体の書き直しなので、
     * 2つ動いていると後から書いた側の写しが勝つ。混ぜ合わせる手立ては無い。</p>
     *
     * <p>ポートではなく進捗ファイルを見張るのは、{@link #bind} が埋まっているポートの
     * 隣へずれる（＝2つ立つ）作りで、しかも {@code --port} で別のポートを指定されると
     * ポート側の見張りはすり抜けてしまうから。</p>
     */
    private static ProgressLock acquireProgressLock(Path progressFile) throws IOException {
        try {
            return ProgressLock.acquire(progressFile);
        } catch (ProgressLock.AlreadyRunningException e) {
            String holder = e.holder();
            System.out.println();
            System.out.println("  ☕  Java Café はすでに動いています");
            System.out.println();
            System.out.println("      進捗ファイルを別のプロセスが使っています"
                    + (holder == null ? "" : "（" + holder + "）") + "。");
            System.out.println("      2つ同時に動かすと、あとから保存した側で上書きされて");
            System.out.println("      進捗（★・書いたコード・コイン）が消えます。");
            System.out.println();
            System.out.println("      動いているほうをそのまま使ってください。");
            System.out.println("      止めてから立て直すなら:");
            System.out.println("        tools/launch.sh --stop");
            System.out.println();
            System.out.println("      進捗ファイル: " + progressFile.toAbsolutePath());
            System.out.println();
            System.exit(1);
            throw new IllegalStateException("到達しない");   // exit したあとの形式上の戻り
        }
    }

    /**
     * 進捗を読み込む。取り込みに失敗したときは<b>ファイルに手を付けずに</b>終了する。
     *
     * <p>ここへ来るのは「JSONとしては読めたのに、こちらの取り込みで落ちた」ときだけである
     * （読めないファイルは {@code ProgressStore} が退避して作り直す）。
     * <b>利用者の記録は無事なのだから、消してはいけない</b>。黙って作り直すと、
     * 版を上げた直後の不具合1つで★もコードもコインも失われる。</p>
     *
     * <p>そのまま起動しないので、代わりに逃げ道を必ず添える ―― 進捗を捨ててよいなら
     * どこへ退避すればよいかまで出す（退避先は既にある控えを潰さない名前になっている）。</p>
     */
    private static ProgressStore openProgress(Path progressFile, ProgressLock lock) {
        try {
            return new ProgressStore(progressFile);
        } catch (ProgressStore.LoadFailedException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.out.println();
            System.out.println("  ☕  進捗を読み込めませんでした");
            System.out.println();
            System.out.println("      " + progressFile.toAbsolutePath());
            System.out.println("      は JSON としては読めましたが、中身の取り込みに失敗しました。");
            System.out.println();
            System.out.println("        原因: " + cause);
            System.out.println();
            System.out.println("      進捗を守るため、起動しません。");
            System.out.println("      ファイルには手を付けていません"
                    + "（★・書いたコード・コインはそのまま残っています）。");
            System.out.println();
            System.out.println("      アプリを新しくした直後なら、これはアプリ側の不具合です。");
            System.out.println("      前の版に戻すか、上の「原因」ごと知らせてください。");
            System.out.println();
            System.out.println("      進捗を捨ててでも起動したいときは、退避してから起動してください:");
            System.out.println("        mv \"" + progressFile.toAbsolutePath() + "\" \\");
            System.out.println("           \"" + e.suggestedBackup().toAbsolutePath() + "\"");
            System.out.println();
            lock.close();
            System.exit(1);
            throw new IllegalStateException("到達しない");   // exit したあとの形式上の戻り
        }
    }

    /**
     * 既定のブラウザで URL を開く。開けなくても起動は続ける（URL は画面に出してある）。
     */
    private static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] command;
        if (os.contains("mac")) {
            command = new String[] {"open", url};
        } else if (os.contains("win")) {
            command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            command = new String[] {"xdg-open", url};
        }
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            System.out.println("      （ブラウザを開けませんでした。上のURLを開いてください）");
        }
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
