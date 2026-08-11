package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscountReportTest {
    @TempDir
    Path tempDirectory;

    private DiscountReport report;

    @BeforeEach
    void setUp() {
        report = new DiscountReport(tempDirectory);
    }

    @Test
    void writesUtf8Lines() throws Exception {
        Path output = report.write("prices.txt", List.of(800, 0, 900));

        assertEquals(
                List.of("800", "0", "900"),
                Files.readAllLines(output, StandardCharsets.UTF_8)
        );
    }

    @Test
    void eachTestStartsWithoutAnotherTestsFile() {
        assertFalse(Files.exists(tempDirectory.resolve("prices.txt")));
    }
}
