package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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

    static Stream<Arguments> invalidRates() {
        return Stream.of(
                Arguments.of(-1),
                Arguments.of(101)
        );
    }

    @ParameterizedTest(name = "rate={0} is invalid")
    @MethodSource("invalidRates")
    void rejectsInvalidRates(int rate) {
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.discounted(100, rate));
    }
}
