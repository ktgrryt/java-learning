package cafe.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 注文の受付データ（模範解答）。
 *
 * <p>要点は2つ。
 *
 * <ul>
 *   <li><b>1項目で判定できるものは注釈で宣言する。</b>if文で書くより、
 *       「何が正しいか」が型のそばに残る。呼び出し側にも同じ宣言から説明できる。</li>
 *   <li><b>1項目では判定できないものは、複数の項目をまとめて見る。</b>
 *       「クーポンを使うなら1000円以上」は{@code couponCode}だけを見ても
 *       {@code totalYen}だけを見ても判定できない。{@code @AssertTrue}を付けた
 *       booleanのメソッドにすれば、項目間のルールも同じ仕組みで扱える。</li>
 * </ul>
 */
public record OrderRequest(
        @NotBlank(message = "品名を入力してください")
        @Size(max = 40, message = "品名は40文字以内にしてください")
        String item,

        @Min(value = 1, message = "数量は1以上にしてください")
        @Max(value = 20, message = "数量は20以下にしてください")
        int quantity,

        @Positive(message = "合計金額は1以上にしてください")
        int totalYen,

        @Pattern(regexp = "^[A-Z0-9]{4,10}$", message = "クーポンは英大文字と数字の4〜10文字です")
        String couponCode) {

    /**
     * クーポンを使える組み合わせか。
     *
     * <p>クーポンが無ければこのルールは関係ないので{@code true}。
     * 付いているときだけ、合計金額の下限を見る。
     */
    @AssertTrue(message = "クーポンは合計1000円以上のときに使えます")
    public boolean isCouponUsable() {
        return couponCode == null || couponCode.isBlank() || totalYen >= 1000;
    }
}
