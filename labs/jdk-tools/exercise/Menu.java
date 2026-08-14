package example.tools;

import java.util.List;

/**
 * ビルドしたclassを、実行環境の版と依存moduleの両面から観察するための小さなprogram。
 *
 * 出力は次の2行にします。
 *   first=espresso last=mocha
 *   path=/menu
 */
public final class Menu {
    public static void main(String[] args) {
        List<String> drinks = List.of("espresso", "latte", "mocha");
        /* TODO Java 21で追加されたSequencedCollectionのメソッドで先頭と末尾を取り出す */
        String first = drinks.get(0);
        String last = drinks.get(drinks.size() - 1);
        System.out.println("first=" + first + " last=" + last);
        /* TODO java.net.httpのHttpRequestを組み立て、path=/menu と表示する */
    }
}
