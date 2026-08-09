package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceServiceTest {
    @ParameterizedTest(name = "price={0}, rate={1} -> {2}")
    @CsvSource({
            "1000, 20, 800",
            "500, 100, 0",
            "999, 0, 999",
            "101, 10, 91"
    })
    void discounts(int price, int rate, int expected) {
        assertEquals(expected, PriceService.discounted(price, rate));
    }

    @Test
    void rejectsNegativePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.discounted(-1, 10));
    }

    @Test
    void rejectsRateOverOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.discounted(100, 101));
    }
}
