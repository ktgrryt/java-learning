package jq.judge;

import jq.content.ArtifactCheck;
import jq.content.ArtifactSpec;
import jq.json.MiniJson;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.xml.sax.InputSource;

/** Javaソース以外の提出ファイルを、形式と教材側の宣言的な条件で検証する。 */
public final class ArtifactValidator {

    private ArtifactValidator() {
    }

    public static Result validate(ArtifactSpec spec, String content) {
        Object parsed;
        try {
            parsed = parse(spec, content);
        } catch (InvalidArtifact e) {
            return new Result(false, e.getMessage(), List.of());
        }

        List<CheckResult> results = new ArrayList<>();
        for (ArtifactCheck check : spec.checks()) {
            boolean pass;
            try {
                pass = evaluate(check, content, parsed);
            } catch (RuntimeException e) {
                // 検査式は教材の一部なので、学習者の入力エラーと混同させない。
                throw new IllegalStateException("artifact の検査式が不正です（"
                        + check.message() + "）: " + e.getMessage(), e);
            }
            results.add(new CheckResult(pass, check.message()));
        }
        return new Result(true, "", List.copyOf(results));
    }

    private static Object parse(ArtifactSpec spec, String content) {
        String format = spec.format();
        try {
            return switch (format) {
                case "xml" -> parseXml(content);
                case "json" -> MiniJson.parse(content);
                case "properties" -> parseProperties(content);
                case "yaml" -> spec.checks().stream()
                        .anyMatch(check -> check.type().equals("githubActions"))
                        ? parseGithubActionsYaml(content) : content;
                // SQL / Dockerfile は正規表現による教材固有の検査を行う。
                default -> content;
            };
        } catch (InvalidArtifact e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidArtifact(format.toUpperCase() + "として読めません: " + e.getMessage());
        }
    }

    private static Document parseXml(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) throws SAXException { throw e; }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            return builder.parse(new InputSource(new StringReader(content)));
        } catch (SAXParseException e) {
            throw new InvalidArtifact("XMLとして読めません（" + e.getLineNumber() + "行目、"
                    + e.getColumnNumber() + "列目）: " + e.getMessage());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new InvalidArtifact("XMLとして読めません: " + e.getMessage());
        }
    }

    private static Properties parseProperties(String content) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
            return properties;
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidArtifact("propertiesとして読めません: " + e.getMessage());
        }
    }

    private static boolean evaluate(ArtifactCheck check, String content, Object parsed) {
        return switch (check.type()) {
            case "xpath" -> xpath((Document) parsed, check.expression());
            case "regex" -> regex(content, check.expression());
            case "property" -> Objects.equals(
                    ((Properties) parsed).getProperty(check.expression()), String.valueOf(check.expected()));
            case "jsonPointer" -> Objects.equals(jsonPointer(parsed, check.expression()), check.expected());
            case "githubActions" -> githubActions((GithubWorkflow) parsed, check.expression());
            default -> throw new IllegalStateException("未対応の検査です: " + check.type());
        };
    }

    /**
     * GitHub Actionsで使う範囲へ絞ったYAML読み取り。
     *
     * <p>文字の有無ではなく、どのjobのどのstepへ書かれたかを検査するために使う。
     * 外部YAMLライブラリを増やさず、通常のblock mapping・step list・{@code with}・
     * block scalarを読む。flow styleの{@code with: { key: value }}も扱う。</p>
     */
    private static GithubWorkflow parseGithubActionsYaml(String content) {
        List<YamlLine> lines = yamlLines(content);
        int jobsIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            YamlLine line = lines.get(i);
            if (line.indent() == 0 && keyOf(line.text()).equals("jobs")) {
                if (jobsIndex >= 0) {
                    throw yamlError(line, "jobs が重複しています");
                }
                jobsIndex = i;
            }
        }
        if (jobsIndex < 0) {
            throw new InvalidArtifact("YAMLとして読めません: jobs がありません");
        }

        int end = lines.size();
        for (int i = jobsIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).indent() == 0) {
                end = i;
                break;
            }
        }
        if (jobsIndex + 1 >= end) {
            throw yamlError(lines.get(jobsIndex), "jobs が空です");
        }
        int jobIndent = lines.get(jobsIndex + 1).indent();
        if (jobIndent <= 0) {
            throw yamlError(lines.get(jobsIndex + 1), "jobをjobsの内側へ字下げしてください");
        }

        Map<String, GithubJob> jobs = new LinkedHashMap<>();
        int cursor = jobsIndex + 1;
        while (cursor < end) {
            YamlLine declaration = lines.get(cursor);
            if (declaration.indent() != jobIndent || declaration.text().startsWith("-")) {
                throw yamlError(declaration, "job名の位置が不正です");
            }
            KeyValue jobEntry = keyValue(declaration);
            if (!jobEntry.value().isBlank()) {
                throw yamlError(declaration, "jobの内容は次の行へ字下げしてください");
            }
            int next = cursor + 1;
            while (next < end && lines.get(next).indent() > jobIndent) {
                next++;
            }
            if (jobs.putIfAbsent(jobEntry.key(), parseGithubJob(
                    lines.subList(cursor + 1, next), declaration)) != null) {
                throw yamlError(declaration, "job名が重複しています: " + jobEntry.key());
            }
            cursor = next;
        }
        return new GithubWorkflow(Map.copyOf(jobs));
    }

    private static GithubJob parseGithubJob(List<YamlLine> lines, YamlLine declaration) {
        if (lines.isEmpty()) {
            throw yamlError(declaration, "jobの内容が空です");
        }
        int propertyIndent = lines.get(0).indent();
        if (propertyIndent <= declaration.indent()) {
            throw yamlError(lines.get(0), "jobの項目を字下げしてください");
        }
        Set<String> needs = new LinkedHashSet<>();
        List<WorkflowStep> steps = List.of();
        Set<String> seen = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < lines.size()) {
            YamlLine property = lines.get(cursor);
            if (property.indent() != propertyIndent || property.text().startsWith("-")) {
                throw yamlError(property, "job直下の項目の位置が不正です");
            }
            KeyValue entry = keyValue(property);
            if (!seen.add(entry.key())) {
                throw yamlError(property, "job内の項目が重複しています: " + entry.key());
            }
            int next = cursor + 1;
            while (next < lines.size() && lines.get(next).indent() > propertyIndent) {
                next++;
            }
            List<YamlLine> children = lines.subList(cursor + 1, next);
            if (entry.key().equals("needs")) {
                needs.addAll(yamlStringList(entry.value(), children));
            } else if (entry.key().equals("steps")) {
                if (!entry.value().isBlank()) {
                    throw yamlError(property, "stepsは次の行へlistで書いてください");
                }
                steps = parseWorkflowSteps(children, property);
            }
            cursor = next;
        }
        return new GithubJob(Set.copyOf(needs), List.copyOf(steps));
    }

    private static List<WorkflowStep> parseWorkflowSteps(
            List<YamlLine> lines, YamlLine declaration) {
        if (lines.isEmpty()) {
            throw yamlError(declaration, "steps が空です");
        }
        int stepIndent = lines.get(0).indent();
        List<WorkflowStep> steps = new ArrayList<>();
        int cursor = 0;
        while (cursor < lines.size()) {
            YamlLine first = lines.get(cursor);
            if (first.indent() != stepIndent || !first.text().startsWith("-")) {
                throw yamlError(first, "stepは - で始めてください");
            }
            int next = cursor + 1;
            while (next < lines.size()
                    && !(lines.get(next).indent() == stepIndent
                    && lines.get(next).text().startsWith("-"))) {
                next++;
            }
            steps.add(parseWorkflowStep(lines.subList(cursor, next)));
            cursor = next;
        }
        return steps;
    }

    private static WorkflowStep parseWorkflowStep(List<YamlLine> lines) {
        YamlLine first = lines.get(0);
        String firstEntry = first.text().substring(1).stripLeading();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> with = new LinkedHashMap<>();
        int fieldIndent = first.indent() + 2;
        String nestedUnder = "";
        String blockField = "";
        int blockIndent = -1;

        if (!firstEntry.isBlank()) {
            KeyValue entry = keyValue(new YamlLine(first.number(), fieldIndent, firstEntry));
            fields.put(entry.key(), scalar(entry.value()));
            nestedUnder = entry.key();
        }
        for (int i = 1; i < lines.size(); i++) {
            YamlLine line = lines.get(i);
            if (!blockField.isEmpty() && line.indent() >= blockIndent) {
                fields.merge(blockField, line.text(), (a, b) -> a + "\n" + b);
                continue;
            }
            blockField = "";
            if (line.indent() == fieldIndent) {
                KeyValue entry = keyValue(line);
                String value = scalar(entry.value());
                if (fields.putIfAbsent(entry.key(), value) != null) {
                    throw yamlError(line, "step内の項目が重複しています: " + entry.key());
                }
                nestedUnder = entry.key();
                if (entry.value().equals("|") || entry.value().equals(">")) {
                    blockField = entry.key();
                    blockIndent = fieldIndent + 2;
                    fields.put(entry.key(), "");
                } else if (entry.key().equals("with") && entry.value().strip().startsWith("{")) {
                    with.putAll(flowMap(entry.value(), line));
                }
            } else if (line.indent() > fieldIndent && nestedUnder.equals("with")) {
                KeyValue entry = keyValue(line);
                if (with.putIfAbsent(entry.key(), scalar(entry.value())) != null) {
                    throw yamlError(line, "with内の項目が重複しています: " + entry.key());
                }
            } else if (line.indent() <= fieldIndent) {
                throw yamlError(line, "step内の字下げが不正です");
            }
        }
        return new WorkflowStep(fields.getOrDefault("uses", ""),
                fields.getOrDefault("run", ""), Map.copyOf(with));
    }

    private static List<YamlLine> yamlLines(String content) {
        List<YamlLine> lines = new ArrayList<>();
        String[] rawLines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            if (indent < raw.length() && raw.charAt(indent) == '\t') {
                throw new InvalidArtifact("YAMLとして読めません（" + (index + 1)
                        + "行目）: 字下げにtabは使えません");
            }
            String text = stripYamlComment(raw.substring(indent)).stripTrailing();
            if (text.isBlank() || (indent == 0 && (text.equals("---") || text.equals("...")))) {
                continue;
            }
            lines.add(new YamlLine(index + 1, indent, text));
        }
        if (lines.isEmpty()) {
            throw new InvalidArtifact("YAMLとして読めません: 内容が空です");
        }
        return lines;
    }

    private static String stripYamlComment(String text) {
        boolean single = false;
        boolean doub = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !doub) single = !single;
            if (c == '"' && !single && (i == 0 || text.charAt(i - 1) != '\\')) doub = !doub;
            if (c == '#' && !single && !doub && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static KeyValue keyValue(YamlLine line) {
        int colon = line.text().indexOf(':');
        if (colon <= 0) {
            throw yamlError(line, "key: value の形で書いてください");
        }
        String key = scalar(line.text().substring(0, colon).strip());
        if (key.isBlank()) {
            throw yamlError(line, "keyが空です");
        }
        return new KeyValue(key, line.text().substring(colon + 1).strip());
    }

    private static String keyOf(String text) {
        int colon = text.indexOf(':');
        return colon <= 0 ? "" : scalar(text.substring(0, colon).strip());
    }

    private static String scalar(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2
                && ((stripped.startsWith("\"") && stripped.endsWith("\""))
                || (stripped.startsWith("'") && stripped.endsWith("'")))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static Set<String> yamlStringList(String value, List<YamlLine> children) {
        Set<String> values = new LinkedHashSet<>();
        String scalar = scalar(value);
        if (scalar.startsWith("[") && scalar.endsWith("]")) {
            for (String part : scalar.substring(1, scalar.length() - 1).split(",")) {
                if (!scalar(part).isBlank()) values.add(scalar(part));
            }
        } else if (!scalar.isBlank()) {
            values.add(scalar);
        }
        for (YamlLine child : children) {
            if (!child.text().startsWith("-")) {
                throw yamlError(child, "needsのlistは - で始めてください");
            }
            String item = scalar(child.text().substring(1));
            if (!item.isBlank()) values.add(item);
        }
        return values;
    }

    private static Map<String, String> flowMap(String value, YamlLine line) {
        String stripped = value.strip();
        if (!stripped.startsWith("{") || !stripped.endsWith("}")) {
            throw yamlError(line, "withのflow mappingを } で閉じてください");
        }
        Map<String, String> result = new LinkedHashMap<>();
        String body = stripped.substring(1, stripped.length() - 1);
        if (body.isBlank()) return result;
        for (String part : body.split(",")) {
            KeyValue entry = keyValue(new YamlLine(line.number(), line.indent(), part.strip()));
            result.put(entry.key(), scalar(entry.value()));
        }
        return result;
    }

    private static InvalidArtifact yamlError(YamlLine line, String message) {
        return new InvalidArtifact("YAMLとして読めません（" + line.number() + "行目）: " + message);
    }

    private static boolean githubActions(GithubWorkflow workflow, String requirement) {
        GithubJob build = workflow.jobs().get("build");
        GithubJob deploy = workflow.jobs().get("deploy");
        return switch (requirement) {
            case "setup-java-21" -> setupBeforeVerify(build);
            case "maven-verify" -> build != null
                    && build.steps().stream().map(WorkflowStep::run)
                    .noneMatch(run -> run.matches("(?is).*(?:-DskipTests|maven\\.test\\.skip).*"))
                    && build.steps().stream().map(WorkflowStep::run)
                    .anyMatch(run -> run.matches("(?is).*\\b(?:mvn|mvnw)\\b[^\\n]*\\bverify\\b.*"));
            case "supply-chain" -> build != null
                    && build.steps().stream().anyMatch(ArtifactValidator::supplyChainStep);
            case "upload-artifact" -> uploadStep(build) != null;
            case "promote-artifact" -> promotesArtifact(build, deploy);
            default -> throw new IllegalArgumentException("GitHub Actions検査式が不正です: " + requirement);
        };
    }

    private static WorkflowStep uploadStep(GithubJob job) {
        if (job == null) return null;
        boolean verified = false;
        for (WorkflowStep step : job.steps()) {
            if (mavenVerifyStep(step)) {
                verified = true;
            } else if (verified
                    && step.uses().matches("(?i)^actions/upload-artifact@v\\d+(?:\\..*)?$")
                    && step.with().getOrDefault("path", "").matches("(?is).*target/.*\\.jar.*")) {
                return step;
            }
        }
        return null;
    }

    private static boolean setupBeforeVerify(GithubJob job) {
        if (job == null) return false;
        int setupAt = -1;
        int verifyAt = -1;
        for (int i = 0; i < job.steps().size(); i++) {
            WorkflowStep step = job.steps().get(i);
            if (setupAt < 0
                    && step.uses().matches("(?i)^actions/setup-java@v\\d+(?:\\..*)?$")
                    && !step.with().getOrDefault("distribution", "").isBlank()
                    && step.with().getOrDefault("java-version", "").matches("21(?:\\.0+)*")) {
                setupAt = i;
            }
            if (verifyAt < 0 && mavenVerifyStep(step)) {
                verifyAt = i;
            }
        }
        return setupAt >= 0 && (verifyAt < 0 || setupAt < verifyAt);
    }

    private static boolean mavenVerifyStep(WorkflowStep step) {
        return step.run().matches("(?is).*\\b(?:mvn|mvnw)\\b[^\\n]*\\bverify\\b.*");
    }

    private static boolean supplyChainStep(WorkflowStep step) {
        if (!step.uses().isBlank() && step.uses().matches(
                "(?is).*(?:sbom|dependency-review|dependency-check|owasp|trivy|grype|cyclonedx|snyk).*")) {
            return true;
        }
        // 説明文やechoに道具名を書いただけでは確認にならない。実行コマンドとして呼ぶ。
        return step.run().matches(
                "(?is).*(?:^|\\n)\\s*(?:(?:\\./)?(?:mvnw?|gradlew?)\\b[^\\n]*"
                        + "(?:sbom|dependency-check|owasp|cyclonedx)|"
                        + "(?:trivy|grype|snyk|syft)\\b[^\\n]*).*");
    }

    private static boolean promotesArtifact(GithubJob build, GithubJob deploy) {
        WorkflowStep upload = uploadStep(build);
        if (upload == null || deploy == null || !deploy.needs().contains("build")) return false;
        int downloadAt = -1;
        WorkflowStep download = null;
        for (int i = 0; i < deploy.steps().size(); i++) {
            WorkflowStep step = deploy.steps().get(i);
            if (step.uses().matches("(?i)^actions/download-artifact@v\\d+(?:\\..*)?$")) {
                downloadAt = i;
                download = step;
                break;
            }
        }
        if (download == null) return false;
        String uploadName = upload.with().getOrDefault("name", "");
        String downloadName = download.with().getOrDefault("name", "");
        if (!downloadName.equals(uploadName)) return false;
        for (WorkflowStep step : deploy.steps()) {
            if (step.run().matches("(?is).*\\b(?:mvn|mvnw)\\b[^\\n]*\\b(?:package|verify)\\b.*")) {
                return false;
            }
        }
        for (int i = downloadAt + 1; i < deploy.steps().size(); i++) {
            if (deploy.steps().get(i).run().matches("(?is).*\\.jar\\b.*")) return true;
        }
        return false;
    }

    private record GithubWorkflow(Map<String, GithubJob> jobs) { }
    private record GithubJob(Set<String> needs, List<WorkflowStep> steps) { }
    private record WorkflowStep(String uses, String run, Map<String, String> with) { }
    private record YamlLine(int number, int indent, String text) { }
    private record KeyValue(String key, String value) { }

    private static boolean xpath(Document document, String expression) {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            return (Boolean) xpath.evaluate(expression, document, XPathConstants.BOOLEAN);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("XPathが不正です: " + expression, e);
        }
    }

    private static boolean regex(String content, String expression) {
        try {
            return Pattern.compile(expression, Pattern.MULTILINE | Pattern.DOTALL)
                    .matcher(content).find();
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("正規表現が不正です: " + expression, e);
        }
    }

    /** RFC 6901 のエスケープを扱う、読み取り専用の最小 JSON Pointer。 */
    private static Object jsonPointer(Object root, String pointer) {
        if (pointer.isEmpty()) {
            return root;
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("JSON Pointer は / で始めてください: " + pointer);
        }
        Object current = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String token = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return Missing.VALUE;
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return Missing.VALUE;
                }
                if (index < 0 || index >= list.size()) {
                    return Missing.VALUE;
                }
                current = list.get(index);
            } else {
                return Missing.VALUE;
            }
        }
        return current;
    }

    private enum Missing { VALUE }

    public record CheckResult(boolean pass, String message) {
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("pass", pass);
            json.put("message", message);
            return json;
        }
    }

    public record Result(boolean syntaxValid, String syntaxError, List<CheckResult> checks) {
        public int passedCount() {
            int passed = 0;
            for (CheckResult check : checks) {
                if (check.pass()) passed++;
            }
            return passed;
        }

        public boolean allPass() {
            return syntaxValid && passedCount() == checks.size();
        }

        public List<Object> checksJson() {
            List<Object> json = new ArrayList<>();
            for (CheckResult check : checks) json.add(check.toJson());
            return json;
        }
    }

    private static final class InvalidArtifact extends RuntimeException {
        private static final long serialVersionUID = 1L;
        InvalidArtifact(String message) { super(message); }
    }
}
