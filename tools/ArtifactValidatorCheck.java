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

        ArtifactSpec genericYaml = new ArtifactSpec("config.yml", "yaml", List.of(
                new ArtifactCheck("regex", "(?m)^enabled:\\s*true$", null, "有効にする")
        ));
        assertResult("GitHub Actions以外のYAML", ArtifactValidator.validate(genericYaml,
                "enabled: true\n"), true, 1);

        ArtifactSpec workflow = new ArtifactSpec(".github/workflows/ci.yml", "yaml", List.of(
                new ArtifactCheck("githubActions", "setup-java-21", null, "JDKを固定"),
                new ArtifactCheck("githubActions", "maven-verify", null, "verifyを実行"),
                new ArtifactCheck("githubActions", "supply-chain", null, "供給網を確認"),
                new ArtifactCheck("githubActions", "upload-artifact", null, "成果物を保存"),
                new ArtifactCheck("githubActions", "promote-artifact", null, "同じ成果物を配備")
        ));
        assertResult("構造が正しいGitHub Actions", ArtifactValidator.validate(workflow, """
                name: ci
                on: [push]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: '21'
                      - name: verify
                        run: mvn -B verify
                      - name: SBOM
                        run: mvn -B cyclonedx:makeAggregateBom
                      - uses: actions/upload-artifact@v4
                        with:
                          name: verified-app
                          path: target/*.jar
                  deploy:
                    needs: [build]
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/download-artifact@v4
                        with: { name: verified-app }
                      - run: ./deploy.sh target/app.jar
                """), true, 5);

        // 単語がファイルのどこかにあるだけでは合格にしない。各設定が同じstep・正しいjobに
        // 属し、build→deployの依存関係を作っていることを検査する。
        assertResult("単語だけを散らしたGitHub Actions", ArtifactValidator.validate(workflow, """
                name: fake-ci
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/setup-java@v4
                      - run: echo distribution=temurin java-version=21
                      - run: mvn test
                      - run: echo cyclonedx
                  deploy:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/upload-artifact@v4
                        with:
                          path: target/fake.jar
                      - uses: actions/download-artifact@v4
                      - run: ./deploy.sh target/fake.jar
                """), false, 0);
        assertSyntaxError("jobの字下げが壊れたYAML", ArtifactValidator.validate(workflow, """
                name: broken
                jobs:
                build:
                  steps:
                    - run: mvn verify
                """));

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
