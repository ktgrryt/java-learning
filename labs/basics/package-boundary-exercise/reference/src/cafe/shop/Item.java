package cafe.shop;

/**
 * 1つの商品。ここが「見える範囲」を決める中心のクラスです。
 *
 * このファイルには public なクラスを2つ書けません（ファイル名と同じ Item だけ）。
 */
public class Item {

    // ① クラスの外から直接触らせない
    private String name;
    private int stock;

    public Item(String name, int stock) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name は空にできません");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("stock は 0 以上です");
        }
        this.name = name;
        this.stock = stock;
    }

    public String name() {
        return name;
    }

    public int stock() {
        return stock;
    }

    // ② 修飾子なし（パッケージプライベート）。同じ cafe.shop の中だけから呼べる
    void reduce(int count) {
        if (count < 0 || count > stock) {
            throw new IllegalArgumentException("count が在庫を超えています");
        }
        stock -= count;
    }

    // ③ protected。別パッケージでも「サブクラスなら」呼べる
    protected String label() {
        return name + " x" + stock;
    }
}
