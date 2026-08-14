package cafe.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 割引後の価格を1行1件で書き出す。
 *
 * 変更不能な受け入れテストが2件失敗する。まず`mvn test`を実行し、
 * 報告書を読んでから直すこと。
 */
public final class DiscountReport {
    private final Path outputDirectory;

    public DiscountReport(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public Path write(String fileName, List<Integer> prices) throws IOException {
        /* TODO 出力先が無いときの扱い */
        Path output = outputDirectory.resolve(fileName);
        List<String> lines = prices.stream().map(String::valueOf).toList();
        Files.write(output, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return output;
    }
}
