package cafe.app;

import cafe.core.Greeter;

/** 使う側。参照専用です。 */
public class Main {

    public static void main(String[] args) {
        Greeter greeter = new Greeter();
        System.out.println(greeter.greet("田中"));
        System.out.println(greeter.greet("佐藤"));
    }
}
