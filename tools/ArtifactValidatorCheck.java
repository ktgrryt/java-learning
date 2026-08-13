import jq.content.ArtifactCheck;
import jq.content.ArtifactSpec;
import jq.judge.ArtifactValidator;

import java.util.List;

/** artifact 検証の正常系・不合格・危険なXML拒否を、外部ライブラリなしで回帰検査する。 */
public final class ArtifactValidatorCheck {

    private ArtifactValidatorCheck() {
    }

    public static void main(String[] args) {
        ArtifactSpec xml = new ArtifactSpec("server.xml", "xml", List.of(
                new ArtifactCheck("xpath", "name(/*) = 'server'", null, "serverがルート"),
                new ArtifactCheck("xpath", "count(/server/feature) = 1", null, "featureが1個")
        ));
        assertResult("正しいXML", ArtifactValidator.validate(xml,
                "<server><feature>restfulWS-4.0</feature></server>"), true, 2);
        assertResult("要件を満たさないXML", ArtifactValidator.validate(xml,
                "<server></server>"), false, 1);
        assertSyntaxError("閉じタグのないXML", ArtifactValidator.validate(xml,
                "<server><feature></server>"));
        assertSyntaxError("DOCTYPEを含むXML", ArtifactValidator.validate(xml,
                "<!DOCTYPE server [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                        + "<server>&xxe;</server>"));

        ArtifactSpec json = new ArtifactSpec("openapi.json", "json", List.of(
                new ArtifactCheck("jsonPointer", "/openapi", "3.1.0", "OpenAPI版")
        ));
        assertResult("JSON Pointer", ArtifactValidator.validate(json,
                "{\"openapi\":\"3.1.0\"}"), true, 1);
        assertSyntaxError("壊れたJSON", ArtifactValidator.validate(json, "{]"));

        ArtifactSpec properties = new ArtifactSpec("app.properties", "properties", List.of(
                new ArtifactCheck("property", "app.timeout", "30", "timeout")
        ));
        assertResult("propertiesの値", ArtifactValidator.validate(properties,
                "app.timeout=30\n"), true, 1);

        ArtifactSpec dockerfile = new ArtifactSpec("Dockerfile", "dockerfile", List.of(
                new ArtifactCheck("regex", "(?m)^FROM\\s+[^\\s]+", null, "FROMがある")
        ));
        assertResult("テキスト系artifactの正規表現", ArtifactValidator.validate(dockerfile,
                "FROM eclipse-temurin:21-jre\n"), true, 1);

        System.out.println("artifact validator: すべて合格");
    }

    private static void assertResult(String label, ArtifactValidator.Result result,
                                     boolean allPass, int passed) {
        if (!result.syntaxValid() || result.allPass() != allPass || result.passedCount() != passed) {
            throw new AssertionError(label + ": syntaxValid=" + result.syntaxValid()
                    + ", allPass=" + result.allPass() + ", passed=" + result.passedCount());
        }
    }

    private static void assertSyntaxError(String label, ArtifactValidator.Result result) {
        if (result.syntaxValid() || result.syntaxError().isBlank() || result.allPass()) {
            throw new AssertionError(label + ": 不正な内容が拒否されませんでした");
        }
    }
}
