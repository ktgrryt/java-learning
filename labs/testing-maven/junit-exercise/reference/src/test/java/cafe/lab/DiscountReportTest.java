package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * DiscountReportのテスト。
 *
 * 出力先はテストごとに@TempDirで用意し、JUnitに片付けさせる。入力が複数あるものは
 * @MethodSourceで表にし、期待値と一緒に読めるようにする。
 */
class DiscountReportTest {

    @TempDir
    Path outputDirectory;

    private DiscountReport report;

    @BeforeEach
    void setUp() {
        report = new DiscountReport(outputDirectory);
    }

    static Stream<Arguments> priceLists() {
        return Stream.of(
                Arguments.of("prices.txt", List.of(800, 0, 900), List.of("800", "0", "900")),
                Arguments.of("single.txt", List.of(120), List.of("120")),
                Arguments.of("empty.txt", List.of(), List.of())
        );
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @MethodSource("priceLists")
    void writesOneLinePerPrice(String fileName, List<Integer> prices, List<String> expected)
            throws Exception {
        Path output = report.write(fileName, prices);

        assertEquals(expected, Files.readAllLines(output, StandardCharsets.UTF_8));
    }

    @Test
    void eachTestStartsWithoutAnotherTestsFile() {
        assertFalse(Files.exists(outputDirectory.resolve("prices.txt")));
    }
}
