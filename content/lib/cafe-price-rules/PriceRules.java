/**
 * 割引価格を出す、カフェの値付けルール。<b>テストされる側</b>のコードとして用意してある。
 *
 * <p>仕様は3つだけ。
 * <ol>
 *   <li>{@code price} が負、または {@code rate} が 0〜100 の外なら
 *       {@link IllegalArgumentException} を投げる</li>
 *   <li>{@code rate} が 0 なら価格はそのまま</li>
 *   <li>{@code rate} が 100 なら 0 円</li>
 * </ol>
 *
 * <p>このクラスには <b>4つの版</b> がある。{@link #select(int)} で切り替える。
 * 版0だけが仕様どおりで、1〜3にはそれぞれ違う不具合が1つ混ざっている。同じテストを
 * 別の版に当てると結果が変わる ——「作ったテストは、壊れたコードを本当に捕まえられるのか」を
 * 確かめるための仕掛け。実務でいえば、同じテストを毎回のビルドに当てるのと同じこと。
 */
public final class PriceRules {

    /** いま選ばれている版。既定は仕様どおりの版0。 */
    private static int variant = 0;

    private PriceRules() {
    }

    /**
     * 使う版を選ぶ。
     *
     * @param value 0（仕様どおり）〜3
     * @throws IllegalArgumentException 範囲外の版を指定したとき
     */
    public static void select(int value) {
        if (value < 0 || value > 3) {
            throw new IllegalArgumentException("版は0〜3です: " + value);
        }
        variant = value;
    }

    /**
     * 割引後の価格を返す。端数は切り捨て。
     *
     * @param price 割引前の価格。0以上
     * @param rate  割引率。0〜100
     * @return 割引後の価格
     */
    public static int discounted(int price, int rate) {
        return switch (variant) {
            case 1 -> clampedRate(price, rate);
            case 2 -> noValidation(price, rate);
            case 3 -> hiddenDefaultRate(price, rate);
            default -> correct(price, rate);
        };
    }

    /** 版0：仕様どおり。 */
    private static int correct(int price, int rate) {
        validate(price, rate);
        return price - price * rate / 100;
    }

    /** 版1：割引率の上限を 99 に丸めてしまう。100%引きにしても1円以上残る。 */
    private static int clampedRate(int price, int rate) {
        validate(price, rate);
        int applied = Math.min(rate, 99);
        return price - price * applied / 100;
    }

    /** 版2：入力の検査が抜けている。負の価格をそのまま計算してしまう。 */
    private static int noValidation(int price, int rate) {
        return price - price * rate / 100;
    }

    /** 版3：割引率 0 のときに、既定割引の5%を勝手に掛けてしまう。 */
    private static int hiddenDefaultRate(int price, int rate) {
        validate(price, rate);
        int applied = rate == 0 ? 5 : rate;
        return price - price * applied / 100;
    }

    /** 仕様1の入力検査。 */
    private static void validate(int price, int rate) {
        if (price < 0 || rate < 0 || rate > 100) {
            throw new IllegalArgumentException("price=" + price + " rate=" + rate);
        }
    }
}
