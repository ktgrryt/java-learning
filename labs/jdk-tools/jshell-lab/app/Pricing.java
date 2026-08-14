package cafe.pricing;

/** 配布物に入れる計算。参照専用（変更しません）。 */
public final class Pricing {

    private Pricing() {
    }

    /** 税込み価格。端数は切り捨て。 */
    public static int withTax(int price, int percent) {
        return price + price * percent / 100;
    }

    /** 複数個の合計。 */
    public static int total(int price, int count, int percent) {
        return withTax(price, percent) * count;
    }
}
