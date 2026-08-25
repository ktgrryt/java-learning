package jq.progress;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 進捗ファイルを1つのプロセスだけが持つようにする錠。
 *
 * <p>{@link ProgressStore} は進捗を丸ごとメモリに持ち、保存はファイル**全体**の書き直しになる。
 * そのため同じ {@code progress.json} を見るサーバが2つ動くと、後から書いた側の写しが勝ち、
 * もう一方でやったぶんが黙って消える。☆を1つ押しただけでも、もう一方の窓の下書きが飛ぶ。</p>
 *
 * <p>2つ立ってしまう道はふさぎにくい ―― {@code jq.App} はポートが埋まっていると +1 して
 * 隣に立つので、アプリ版を起動したまま {@code ./run.sh} を叩くと成立してしまう。
 * ポートを見張るのではなく<b>進捗ファイルそのものを錠で守る</b>ことにしたのは、
 * ポートを変えて起動されても効くのはこちらだけだから。</p>
 *
 * <p>錠は {@code progress.json.lock} に掛ける。進捗ファイル自体に掛けないのは、
 * 書き出しが「一時ファイルへ書いて置き換える」形で、本体のiノードが入れ替わるため
 * （置き換えた瞬間に錠が本体から外れてしまう）。</p>
 *
 * <p>OSが持つ錠なので、{@code kill -9} や電源断で落ちても残らない。
 * 錠のファイルは消さずに置いておく（消す側と掛ける側が競り合うのを避けるため）。</p>
 */
public final class ProgressLock implements AutoCloseable {

    /** すでに他のプロセスが進捗ファイルを持っていた。 */
    public static final class AlreadyRunningException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String holder;

        AlreadyRunningException(Path lockFile, String holder) {
            super("進捗ファイルは他のプロセスが使っています: " + lockFile);
            this.holder = holder;
        }

        /** 持っている相手の手がかり（PIDなど）。分からなければ null。 */
        public String holder() {
            return holder;
        }
    }

    private final FileChannel channel;

    /** 掛かっている錠。錠を扱えないファイルシステムでは null（警告だけ出して先へ進む）。 */
    private final FileLock lock;

    private ProgressLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 進捗ファイルの錠を取る。
     *
     * @throws AlreadyRunningException 他のプロセスが持っていた（起動してはいけない）
     * @throws IOException            錠のファイルを作れなかった
     */
    public static ProgressLock acquire(Path progressFile) throws AlreadyRunningException, IOException {
        Path lockFile = progressFile.resolveSibling(progressFile.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // 同じJVMがすでに持っている。本番の経路（jq.App）では1度しか呼ばないので、
            // ここへ来るのは呼び方を間違えたとき
            closeQuietly(channel);
            throw new AlreadyRunningException(lockFile, "同じプロセスが二重に取得しようとしました");
        } catch (IOException e) {
            // 錠を扱えないファイルシステム（一部のネットワーク越しなど）。
            // 守りが1枚減るだけなので、起動そのものは止めない
            System.err.println("注意: 進捗ファイルの排他ロックを掛けられませんでした ("
                    + e.getMessage() + ")。");
            System.err.println("      2つ以上同時に起動すると、進捗が上書きされることがあります。");
            return new ProgressLock(channel, null);
        }
        if (lock == null) {
            String holder = readHolder(lockFile);
            closeQuietly(channel);
            throw new AlreadyRunningException(lockFile, holder);
        }
        writeHolder(channel);
        return new ProgressLock(channel, lock);
    }

    /**
     * 誰が持っているかを錠のファイルへ書く。断られた側の案内に出すためだけのもので、
     * 排他そのものはOSの錠が担う（書けなくても構わない）。
     */
    private static void writeHolder(FileChannel channel) {
        String text = "PID " + ProcessHandle.current().pid();
        try {
            channel.truncate(0);
            channel.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            channel.force(false);
        } catch (IOException ignored) {
            // 案内が少し不親切になるだけ
        }
    }

    /** 持っている相手の手がかりを読む。読めなければ null。 */
    private static String readHolder(Path lockFile) {
        try {
            String text = Files.readString(lockFile, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // 閉じられなくても、プロセスが終われば手放す
        }
    }

    /**
     * 錠を手放す。進捗を書き出したあとに呼ぶこと
     * （先に手放すと、書いている途中に別のプロセスが入れる）。
     */
    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 手放せなくても、プロセスが終われば OS が外す
        }
        closeQuietly(channel);
    }
}
