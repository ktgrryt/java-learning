package cafe.greeting.internal;

/**
 * 挨拶文の組み立て。moduleの内部実装であり、外へ公開しない。
 *
 * このpackageを公開すると、利用側が内部の都合へ依存できてしまう。
 */
public final class Formatter {
    private Formatter() {
    }

    public static String decorate(String name) {
        return "Hello, " + name + "!";
    }
}
