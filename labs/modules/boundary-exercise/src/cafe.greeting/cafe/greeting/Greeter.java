package cafe.greeting;

import cafe.greeting.internal.Formatter;

/** 公開するAPI。内部実装は同じmodule内から使う。 */
public final class Greeter {
    private Greeter() {
    }

    public static String hello(String name) {
        return Formatter.decorate(name);
    }
}
