package example.tools;

import java.util.stream.IntStream;

/** JDK付属ツールで観察する小さなprogram。 */
public final class ToolDemo {
    private ToolDemo() {}

    public static int sumTo(int n) {
        return IntStream.rangeClosed(1, n).sum();
    }

    public static void main(String[] args) {
        System.out.println(sumTo(10));
    }
}

