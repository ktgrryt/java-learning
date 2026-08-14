package cafe.ops;

/**
 * 試験用の依存先。決めた結果を返す。
 *
 * <p>{@code throwing} を立てると{@link #up()}が例外を投げる。実際の依存先も、
 * 落ちているときは綺麗にfalseを返すのではなく接続例外を投げてくる。
 */
public final class FixedDependencyCheck implements DependencyCheck {

    private final String name;
    private final boolean up;
    private final boolean throwing;

    public FixedDependencyCheck(String name, boolean up) {
        this(name, up, false);
    }

    public FixedDependencyCheck(String name, boolean up, boolean throwing) {
        this.name = name;
        this.up = up;
        this.throwing = throwing;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean up() {
        if (throwing) {
            throw new IllegalStateException("connection refused: inventory-db-07.internal:5432");
        }
        return up;
    }
}
