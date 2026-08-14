import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * テーブル1つ分のロック。参照専用（変更しない）。
 *
 * <p>取得できたあと、{@link #slowWindow} が立っているあいだは40ミリ秒だけ待つ。これは
 * <b>デッドロックを毎回起こすため</b>の仕掛けである。2つの処理が1つ目のロックを同時に
 * 持っている状態を確実に作れるので、取得順がそろっていなければ必ず詰まり、
 * そろっていれば必ず通る。「たまたま再現しない」を無くすために入れてある。
 *
 * <p>取得回数も名前ごとに数える。どちらのロックも実際に取っているかを採点側が確かめる。
 */
public final class TableLock {

    /** 交差の検証だけ窓を開ける。回数を数える段と不変条件の段では閉じて速く回す。 */
    static volatile boolean slowWindow = true;

    private static final Map<String, AtomicInteger> ACQUISITIONS = new ConcurrentHashMap<>();

    private final String name;
    private final ReentrantLock lock = new ReentrantLock();

    public TableLock(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void lock() {
        lock.lock();
        ACQUISITIONS.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
        if (slowWindow) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void unlock() {
        lock.unlock();
    }

    static int acquisitions(String name) {
        AtomicInteger counter = ACQUISITIONS.get(name);
        return counter == null ? 0 : counter.get();
    }
}
