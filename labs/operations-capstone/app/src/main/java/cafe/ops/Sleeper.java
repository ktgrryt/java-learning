package cafe.ops;

/**
 * 待つ処理を差し替えられるようにした窓口。
 *
 * <p>本番では実際に寝るが、試験では{@link RecordingSleeper}に差し替えて
 * 「何ミリ秒待つつもりだったか」だけを記録する。実際に待たないので採点は速く、
 * 待ち時間の伸び方は機械によらず同じ結果になる。
 */
public interface Sleeper {

    void sleep(long millis);
}
