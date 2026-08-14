package cafe.api;

/**
 * 注文の受付データ。
 *
 * <p>制約が<b>1つも宣言されていません</b>。宣言しても、Resource側で有効にしないと
 * 何も起きない（それも別の検査で測ります）。
 *
 * <p>宣言すること:
 *
 * <ul>
 *   <li>{@code item} … 空白だけは不可。40文字以内</li>
 *   <li>{@code quantity} … 1以上20以下</li>
 *   <li>{@code totalYen} … 1以上（0や負は不可）</li>
 *   <li>{@code couponCode} … 省略可。書くなら英大文字と数字だけの4〜10文字</li>
 *   <li><b>項目間のルール</b> … クーポンを使うなら{@code totalYen}が1000以上。
 *       1項目だけ見ても判定できないので、複数の項目をまとめて見る形で宣言する</li>
 * </ul>
 *
 * <p>フィールドの名前と並びは採点の足場が送るJSONに合わせてあるので変えないこと。
 */
public record OrderRequest(
        String item,
        int quantity,
        int totalYen,
        String couponCode) {

    // TODO: 項目間のルールを boolean のメソッドとして宣言する。
    //       名前は isCouponUsable() にすること（項目名が couponUsable になり、採点がそれを見る）。
    //       クーポンを使うなら totalYen >= 1000。クーポン無しならこのルールは通す。
}
