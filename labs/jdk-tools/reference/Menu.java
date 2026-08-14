package example.tools;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * ビルドしたclassを、実行環境の版と依存moduleの両面から観察するための小さなprogram。
 *
 * getFirst / getLast はJava 21のSequencedCollectionで追加された。だからこのsourceは
 * --release 17 では通らない。HttpRequestを使うので、必要moduleはjava.baseだけではない。
 */
public final class Menu {
    public static void main(String[] args) {
        List<String> drinks = List.of("espresso", "latte", "mocha");
        String first = drinks.getFirst();
        String last = drinks.getLast();
        System.out.println("first=" + first + " last=" + last);
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:8080/menu"))
                .build();
        System.out.println("path=" + request.uri().getPath());
    }
}
