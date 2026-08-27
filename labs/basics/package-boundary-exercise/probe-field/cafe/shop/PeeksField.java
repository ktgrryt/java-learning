package cafe.shop;

/**
 * Item と **同じパッケージ** にいる別のクラスから、フィールドを直接読もうとする形。
 * これも **コンパイルできてはいけません** ―― `private` は「同じクラスの中だけ」なので、
 * 同じパッケージの仲間にも見せません（修飾子なしのままだと通ってしまいます）。
 */
public class PeeksField {

    public static int peek(Item item) {
        return item.stock;
    }
}
