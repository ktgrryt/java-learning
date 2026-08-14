package cafe.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 割引後の価格を1行1件で書き出す。
 *
 * 出力先が無ければ作る。同じファイル名へ書き直したら、前回の内容は残さない。
 */
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
