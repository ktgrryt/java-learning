package example.tools;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * 縮小ランタイムへ入れるprogram。編集の対象ではない。
 *
 * java.baseの外（java.net.http）を使うので、moduleとして組み立てるには依存の宣言が要る。
 */
public final class Menu {
    public static void main(String[] args) {
        List<String> drinks = List.of("espresso", "latte", "mocha");
        System.out.println("first=" + drinks.getFirst() + " last=" + drinks.getLast());
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:8080/menu"))
                .build();
        System.out.println("path=" + request.uri().getPath());
    }
}
