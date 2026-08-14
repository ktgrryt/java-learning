package cafe.ops;

/** readinessが見る依存先1つ。 */
public interface DependencyCheck {

    String name();

    /** 使える状態か。落ちている場合はfalseを返すか、例外を投げる。 */
    boolean up();
}
