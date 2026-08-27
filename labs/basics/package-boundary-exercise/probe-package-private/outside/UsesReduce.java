package outside;

import cafe.shop.Item;

/**
 * 別パッケージ・サブクラスでもないクラスから在庫を直接減らそうとする形。
 * これは **コンパイルできてはいけません**（TODO ② が正しければ通りません）。
 */
public class UsesReduce {

    public static void main(String[] args) {
        Item item = new Item("コーヒー", 10);
        item.reduce(1);
    }
}
