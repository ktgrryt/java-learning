package cafe.logging;

import cafe.logging.IncidentAnalyzer.Deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class InvestigationTest {
    private static int passed;
    private static Path root;
    private static List<LogEntry> fixture;

    public static void main(String[] args) throws Exception {
        root = Path.of(System.getProperty("lab.root"));
        fixture = LogParser.parse(Files.readAllLines(root.resolve("incident/orders.log")));

        test("基本5項目を解析できる", InvestigationTest::parseCoreFields);
        test("追加項目を解析できる", InvestigationTest::parseAdditionalFields);
        test("入力順をsequenceへ保持する", InvestigationTest::parseSequence);
        test("壊れたログ行を拒否する", InvestigationTest::rejectMalformedLine);
        test("許可項目を安定した順で出す", InvestigationTest::sanitizeStableFields);
        test("秘密情報と自由入力を出さない", InvestigationTest::sanitizeSecrets);
        test("対象requestIdだけを時系列化する", InvestigationTest::timeline);
        test("同時刻では入力順を保つ", InvestigationTest::sameTimeOrder);
        test("最初のERRORを特定する", InvestigationTest::firstError);
        test("エラー以前の最新配備を仮説にする", InvestigationTest::deploymentHypothesis);
        test("報告が事実・仮説・検証を分けている", InvestigationTest::report);

        System.out.println("OK " + passed + "/11 tests");
    }

    private static void parseCoreFields() {
        LogEntry entry = LogParser.parseLine(
                "2026-08-11T10:00:00Z|INFO|order|r-1|started|orderId=9", 7);
        equal(Instant.parse("2026-08-11T10:00:00Z"), entry.time());
        equal("INFO", entry.level());
        equal("order", entry.service());
        equal("r-1", entry.requestId());
        equal("started", entry.event());
    }

    private static void parseAdditionalFields() {
        LogEntry entry = LogParser.parseLine(
                "2026-08-11T10:00:00Z|ERROR|db|r-1|timeout|orderId=9 result=POOL_FULL", 0);
        equal("9", entry.fields().get("orderId"));
        equal("POOL_FULL", entry.fields().get("result"));
    }

    private static void parseSequence() {
        List<LogEntry> parsed = LogParser.parse(List.of(
                "2026-08-11T10:00:00Z|INFO|a|r|one|",
                "2026-08-11T10:00:00Z|INFO|a|r|two|"));
        equal(0, parsed.get(0).sequence());
        equal(1, parsed.get(1).sequence());
    }

    private static void rejectMalformedLine() {
        boolean thrown = false;
        try {
            LogParser.parseLine("INFO|too|short", 0);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        check(thrown, "6区画に満たない行を拒否してください");
    }

    private static void sanitizeStableFields() {
        LogEntry entry = fixture.stream()
                .filter(e -> e.event().equals("connection_timeout"))
                .findFirst().orElseThrow();
        equal("time=2026-08-11T10:02:02Z level=ERROR service=database"
                        + " requestId=r-500 event=connection_timeout orderId=420"
                        + " result=POOL_EXHAUSTED durationMs=1501",
                LogSanitizer.render(entry));
    }

    private static void sanitizeSecrets() {
        String all = fixture.stream()
                .map(LogSanitizer::render)
                .reduce("", (left, right) -> left + "\n" + right);
        check(!all.contains("authorization"), "Authorizationのキーを出してはいけません");
        check(!all.contains("Bearer-prod-secret"), "Authorizationの値を出してはいけません");
        check(!all.contains("customerReason"), "自由入力のキーを出してはいけません");
        check(!all.contains("転居先を秘密にしたい"), "自由入力の値を出してはいけません");
    }

    private static void timeline() {
        List<LogEntry> events = new IncidentAnalyzer(fixture).timeline("r-500");
        equal(5, events.size());
        equal("request_received", events.get(0).event());
        equal("response_sent", events.get(4).event());
        check(events.stream().allMatch(e -> e.requestId().equals("r-500")),
                "別requestIdを混ぜないでください");
    }

    private static void sameTimeOrder() {
        Instant time = Instant.parse("2026-08-11T10:00:00Z");
        List<LogEntry> entries = List.of(
                new LogEntry(time, "INFO", "order", "r", "second-input",
                        Map.of(), 1),
                new LogEntry(time, "INFO", "order", "r", "first-input",
                        Map.of(), 0));
        List<LogEntry> events = new IncidentAnalyzer(entries).timeline("r");
        equal("first-input", events.get(0).event());
        equal("second-input", events.get(1).event());
    }

    private static void firstError() {
        LogEntry error = new IncidentAnalyzer(fixture).firstError("r-500").orElseThrow();
        equal("database", error.service());
        equal("connection_timeout", error.event());
        check(new IncidentAnalyzer(fixture).firstError("r-ok-1").isEmpty(),
                "ERRORのない要求はOptional.emptyにしてください");
    }

    private static void deploymentHypothesis() {
        List<Deployment> deployments = readDeployments();
        Instant errorTime = new IncidentAnalyzer(fixture).firstError("r-500")
                .orElseThrow().time();
        Deployment candidate = IncidentAnalyzer
                .latestDeploymentBefore(errorTime, deployments).orElseThrow();
        equal("orders-2.4.0", candidate.version());
        check(candidate.time().compareTo(errorTime) <= 0,
                "エラーより後の配備を候補にしてはいけません");
    }

    private static void report() {
        try {
            String report = Files.readString(root.resolve("REPORT.md"));
            check(!report.contains("TODO"), "REPORT.mdのTODOをすべて置き換えてください");
            check(report.contains("2026-08-11T10:02:02Z"),
                    "最初の異常の時刻を事実として書いてください");
            check(report.contains("connection_timeout"),
                    "最初の異常イベントを事実として書いてください");
            check(report.contains("orders-2.4.0"),
                    "直前の配備を原因候補として扱ってください");
            for (String heading : List.of("仮説", "検証", "緩和", "恒久")) {
                check(report.contains(heading), "REPORT.mdに「" + heading + "」が必要です");
            }
            check(!report.contains("Bearer-prod-secret"),
                    "報告へAuthorizationの値を転記してはいけません");
            check(!report.contains("転居先を秘密にしたい"),
                    "報告へ顧客の自由入力を転記してはいけません");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Deployment> readDeployments() {
        try {
            List<Deployment> deployments = new ArrayList<>();
            for (String line : Files.readAllLines(root.resolve("incident/deployments.txt"))) {
                String[] parts = line.split("\\|", -1);
                deployments.add(new Deployment(Instant.parse(parts[0]), parts[1]));
            }
            return deployments;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            System.err.println("FAIL " + name + ": " + error.getMessage());
            throw error;
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
