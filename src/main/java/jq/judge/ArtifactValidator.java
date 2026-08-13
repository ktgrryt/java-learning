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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
            parsed = parse(spec.format(), content);
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

    private static Object parse(String format, String content) {
        try {
            return switch (format) {
                case "xml" -> parseXml(content);
                case "json" -> MiniJson.parse(content);
                case "properties" -> parseProperties(content);
                // SQL / Dockerfile / YAML は正規表現による教材固有の検査を行う。
                // 外部パーサーを同梱していないため、構文全体を読めたとは表示しない。
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
            default -> throw new IllegalStateException("未対応の検査です: " + check.type());
        };
    }

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
