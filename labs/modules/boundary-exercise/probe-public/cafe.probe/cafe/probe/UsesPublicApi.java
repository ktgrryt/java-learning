package cafe.probe;

import cafe.greeting.Greeter;

/** 公開APIを使う。これはcompileできるはず。 */
public final class UsesPublicApi {
    public static String call() {
        return Greeter.hello("Probe");
    }
}
