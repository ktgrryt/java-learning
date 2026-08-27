package outside;

import cafe.shop.Item;

/**
 * 別パッケージの、サブクラスでないクラスから表示用の1行を呼ぶ形。
 * これも **コンパイルできてはいけません**（`public` にすると通ってしまいます）。
 */
public class UsesLabel {

    public static void main(String[] args) {
        Item item = new Item("コーヒー", 10);
        System.out.println(item.label());
    }
}
