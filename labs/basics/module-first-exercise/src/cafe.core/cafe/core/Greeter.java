package cafe.core;

import cafe.core.internal.Style;

/** 外へ見せるAPI。内部実装（Style）を借りて1行を作る。参照専用です。 */
public class Greeter {

    public String greet(String name) {
        return Style.decorate("いらっしゃいませ、" + name + "さん");
    }
}
