package cafe.pricing;

/** 配布物の入口。参照専用（変更しません）。 */
public final class Main {

    public static void main(String[] args) {
        System.out.println("total=" + Pricing.total(480, 3, 10));
    }
}
