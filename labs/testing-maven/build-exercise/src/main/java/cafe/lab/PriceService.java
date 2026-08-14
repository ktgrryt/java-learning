package cafe.lab;

public final class PriceService {
    private PriceService() {
    }

    public static int discounted(int price, int rate) {
        if (price < 0) {
            throw new IllegalArgumentException("price must be non-negative");
        }
        if (rate < 0 || rate > 100) {
            throw new IllegalArgumentException("rate must be between 0 and 100");
        }
        return price - Math.multiplyExact(price, rate) / 100;
    }
}
