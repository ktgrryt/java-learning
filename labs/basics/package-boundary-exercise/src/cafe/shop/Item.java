package cafe.shop;

/**
 * 1つの商品。ここが「見える範囲」を決める中心のクラスです。
 *
 * このファイルには public なクラスを2つ書けません（ファイル名と同じ Item だけ）。
 */
public class Item {

    // TODO ① 2つのフィールドを、クラスの外から直接触れないようにする
    String name;
    int stock;

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

    // TODO ② 在庫を減らすのは同じパッケージ（cafe.shop）の中だけにする。
    //         別のパッケージからは、サブクラスであっても呼べない形にすること
    public void reduce(int count) {
        if (count < 0 || count > stock) {
            throw new IllegalArgumentException("count が在庫を超えています");
        }
        stock -= count;
    }

    // TODO ③ 表示用の1行は、別パッケージの「サブクラス」からは使えるが、
    //         サブクラスでない別パッケージのクラスからは使えない形にする
    public String label() {
        return name + " x" + stock;
    }
}
