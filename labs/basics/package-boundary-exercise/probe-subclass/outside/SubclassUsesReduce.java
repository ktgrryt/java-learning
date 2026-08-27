package outside;

import cafe.shop.Item;

/**
 * 別パッケージの **サブクラス** から在庫を直接減らそうとする形。
 * ここも **コンパイルできてはいけません** ―― 在庫を減らせるのは同じパッケージの中だけで、
 * `protected` にしてしまうとこれが通ってしまいます。
 */
public class SubclassUsesReduce extends Item {

    public SubclassUsesReduce(String name, int stock) {
        super(name, stock);
    }

    public void cheat() {
        reduce(1);
    }
}
