package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * DiscountReportのテスト。
 *
 * 今は一時ディレクトリを自分で作って消していない。テストが増えるほど、前のテストが残した
 * ファイルに次のテストが影響される。
 *
 * TODO 1: @TempDirのPathフィールドと@BeforeEachで、テストごとに空の出力先を用意する
 * TODO 2: 複数の入力を1つの検証にまとめる。@ParameterizedTestと@MethodSourceを使い、
 *         供給メソッドは2件以上のArgumentsを返すstaticメソッドにする
 */
class DiscountReportTest {

    @Test
    void writesOneLinePerPrice() throws Exception {
        Path directory = Files.createTempDirectory("discount-report");
        DiscountReport report = new DiscountReport(directory);

        Path output = report.write("prices.txt", List.of(800));

        assertEquals(List.of("800"), Files.readAllLines(output, StandardCharsets.UTF_8));
    }
}
