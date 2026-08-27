package cafe.core.internal;

/**
 * 内部実装。**public なクラスですが、モジュールの外へは出しません。**
 * 参照専用です。
 */
public class Style {

    public static String decorate(String text) {
        return "☕ " + text;
    }
}
