package cafe.probe;

import cafe.greeting.internal.Formatter;

/** 内部実装を使う。公開されていなければcompileできないはず。 */
public final class UsesInternal {
    public static String call() {
        return Formatter.decorate("Probe");
    }
}
