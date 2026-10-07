package example.greeting;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実動作だけでは見えない、学習目標に直結する宣言を固定する。 */
class RequirementsTest {
    // ひな形のTODOコメントにも同じ語が出てくるので、コメントを除いてから調べる
    @Test void learnerTestUsesQuarkusTestAndHttpBoundary() throws Exception {
        String source = withoutComments(Files.readString(Path.of(
                "src/test/java/example/greeting/GreetingResourceTest.java")));
        assertTrue(source.contains("@QuarkusTest"), "GreetingResourceTestを@QuarkusTestにしてください");
        assertTrue(source.contains("/api/greeting"), "業務APIをHTTPでテストしてください");
        assertTrue(source.contains("/q/health/ready"), "readinessをHTTPでテストしてください");
        assertTrue(count(source, ".statusCode(") >= 3,
                "正常・入力不正・readinessの3つについて、statusCodeを確かめてください");
    }

    @Test void serviceUsesNamedConfigProperty() throws Exception {
        String source = withoutComments(Files.readString(Path.of(
                "src/main/java/example/greeting/GreetingService.java")));
        assertTrue(source.contains("@ConfigProperty"), "ConfigPropertyを注入してください");
        assertTrue(source.contains("app.greeting.prefix"), "指定された設定キーを使ってください");
        assertFalse(source.contains("\"Welcome"), "prefixの値をコードへ直接書かず、設定から受け取ってください");
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static int count(String text, String word) {
        int found = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            found++;
        }
        return found;
    }
}
