package outside;

import cafe.core.internal.Style;

/**
 * モジュールの外から、公開していないパッケージを使おうとする形。
 * **コンパイルできてはいけません** ―― `Style` は `public` ですが、
 * `cafe.core.internal` を `exports` していなければ外からは見えません。
 * 参照専用です。
 */
public class UsesInternal {

    public static void main(String[] args) {
        System.out.println(Style.decorate("外から呼べてしまった"));
    }
}
