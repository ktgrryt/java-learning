package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 学習者が変更できない受け入れテスト。
 *
 * 前半はDiscountReportの仕様を実際の入出力で固定する。後半は「保守しやすいテストの書き方」を
 * 学習者のテストクラスの形として固定する。どちらもこのファイルでは直せない。
 */
class RequirementsTest {

    @TempDir
    Path workspace;

    @Test
    void createsOutputDirectoryWhenMissing() throws Exception {
        Path missing = workspace.resolve("reports").resolve("2026-08");
        DiscountReport report = new DiscountReport(missing);

        Path output = report.write("prices.txt", List.of(800, 0));

        assertEquals(List.of("800", "0"), Files.readAllLines(output, StandardCharsets.UTF_8));
    }

    @Test
    void replacesPreviousContentWhenWritingAgain() throws Exception {
        DiscountReport report = new DiscountReport(workspace);
        report.write("prices.txt", List.of(800, 0, 900));

        Path output = report.write("prices.txt", List.of(120));

        assertEquals(List.of("120"), Files.readAllLines(output, StandardCharsets.UTF_8));
    }

    @Test
    void learnerTestPreparesItsOwnDirectoryForEachTest() {
        boolean tempDirectory = Arrays.stream(DiscountReportTest.class.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(TempDir.class)
                        && field.getType() == Path.class);
        assertTrue(tempDirectory,
                "DiscountReportTestへ@TempDirのPathフィールドを用意してください");

        boolean beforeEach = Arrays.stream(DiscountReportTest.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(BeforeEach.class));
        assertTrue(beforeEach, "@BeforeEachで各テストの準備を閉じてください");
    }

    @Test
    void learnerTestTablesInputsWithMethodSource() throws Exception {
        Method parameterized = Arrays.stream(DiscountReportTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ParameterizedTest.class)
                        && method.isAnnotationPresent(MethodSource.class))
                .findFirst()
                .orElse(null);
        assertTrue(parameterized != null,
                "@ParameterizedTestと@MethodSourceを使うテストを1つ書いてください");

        String[] sources = parameterized.getAnnotation(MethodSource.class).value();
        assertTrue(sources.length == 1 && !sources[0].isBlank(),
                "@MethodSourceへ供給メソッドの名前を1つ指定してください");

        Method supplier = DiscountReportTest.class.getDeclaredMethod(sources[0]);
        assertTrue(java.lang.reflect.Modifier.isStatic(supplier.getModifiers()),
                sources[0] + "はstaticにしてください");
        supplier.setAccessible(true);
        Object supplied = supplier.invoke(null);
        assertTrue(supplied instanceof Stream<?>,
                sources[0] + "はStreamを返してください");
        assertTrue(((Stream<?>) supplied).count() >= 2,
                sources[0] + "は2件以上の入力を供給してください");
    }
}
