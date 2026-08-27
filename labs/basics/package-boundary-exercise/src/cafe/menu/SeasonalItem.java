package cafe.menu;

import cafe.shop.Item;

/**
 * 季節限定の商品。`cafe.shop` とは別のパッケージにいる Item のサブクラスです。
 *
 * 別パッケージなので、`Item` の private なフィールドには触れません。
 * 表示用の1行だけを親から借りて、季節の名前を足します。
 */
public class SeasonalItem extends Item {

    private final String season;

    public SeasonalItem(String name, int stock, String season) {
        super(name, stock);
        this.season = season;
    }

    public String note() {
        // TODO ④ 親の「表示用の1行」を使って、`春の <1行>` の形を返す
        //         （name と stock を直接読もうとすると、private なのでコンパイルできません）
        return season + "の " + "???";
    }
}
