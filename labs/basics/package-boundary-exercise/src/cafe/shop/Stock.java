package cafe.shop;

/**
 * 在庫を動かす係。Item と同じパッケージなので、パッケージプライベートのメソッドを呼べます。
 *
 * このファイルは参照専用です（書き換えられません）。
 */
public class Stock {

    public static void sell(Item item, int count) {
        item.reduce(count);
    }
}
