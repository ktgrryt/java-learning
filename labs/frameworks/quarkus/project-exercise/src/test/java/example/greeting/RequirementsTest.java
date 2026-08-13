package example.greeting;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実動作だけでは見えない、学習目標に直結する宣言を固定する。 */
class RequirementsTest {
    @Test void learnerTestUsesQuarkusTestAndHttpBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/test/java/example/greeting/GreetingResourceTest.java"));
        assertTrue(source.contains("@QuarkusTest"), "GreetingResourceTestを@QuarkusTestにしてください");
        assertTrue(source.contains("/api/greeting"), "業務APIをHTTPでテストしてください");
        assertTrue(source.contains("/q/health/ready"), "readinessをHTTPでテストしてください");
    }

    @Test void serviceUsesNamedConfigProperty() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/example/greeting/GreetingService.java"));
        assertTrue(source.contains("@ConfigProperty"), "ConfigPropertyを注入してください");
        assertTrue(source.contains("app.greeting.prefix"), "指定された設定キーを使ってください");
    }
}
