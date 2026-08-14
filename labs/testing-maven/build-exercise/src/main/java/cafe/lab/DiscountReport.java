package cafe.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DiscountReport {
    private final Path outputDirectory;

    public DiscountReport(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public Path write(String fileName, List<Integer> prices) throws IOException {
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(fileName);
        List<String> lines = prices.stream().map(String::valueOf).toList();
        Files.write(output, lines, StandardCharsets.UTF_8);
        return output;
    }
}
