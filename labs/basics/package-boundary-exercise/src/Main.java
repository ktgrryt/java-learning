import cafe.menu.SeasonalItem;
import cafe.shop.Item;
import cafe.shop.Stock;

/**
 * 動かして確かめる側。参照専用です（書き換えられません）。
 *
 * パッケージのないファイル（既定パッケージ）から、2つのパッケージを import しています。
 */
public class Main {

    public static void main(String[] args) {
        Item coffee = new Item("コーヒー", 10);
        System.out.println(coffee.name() + " " + coffee.stock());

        // 同じパッケージの Stock は、Item の在庫を減らせる
        Stock.sell(coffee, 3);
        System.out.println(coffee.name() + " " + coffee.stock());

        SeasonalItem sakura = new SeasonalItem("さくらラテ", 5, "春");
        System.out.println(sakura.note());
        Stock.sell(sakura, 5);
        System.out.println(sakura.note());
    }
}
