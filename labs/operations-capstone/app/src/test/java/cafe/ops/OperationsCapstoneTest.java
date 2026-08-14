package cafe.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 運用統合演習の受け入れ条件。
 *
 * <p>速さは秒で測らない。問い合わせ回数・試行回数・待ち時間の記録で測るので、
 * どの機械でも同じ結果になる。
 */
public final class OperationsCapstoneTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("契約どおり新しい注文から並べる", OperationsCapstoneTest::listOrder);
        run("店舗名をまとめて引き、問い合わせを2回にする", OperationsCapstoneTest::listBulkFetch);
        run("注文が0件なら店舗を引かない", OperationsCapstoneTest::listEmpty);
        run("一時障害は最大3回まで試す", OperationsCapstoneTest::retryUntilSuccess);
        run("再試行の待ち時間を毎回2倍にする", OperationsCapstoneTest::retryBackoff);
        run("打ち切りの応答に下流の内部情報を出さない", OperationsCapstoneTest::retryGivesUp);
        run("恒久障害は再試行しない", OperationsCapstoneTest::permanentFailure);
        run("同じ冪等キーの再送で下流へ二重に送らない", OperationsCapstoneTest::idempotentRetry);
        run("readinessが依存先ごとの状態を返す", OperationsCapstoneTest::readinessUp);
        run("依存先が落ちていればreadyにしない", OperationsCapstoneTest::readinessDown);
        run("expand段階のDB移行を用意する", OperationsCapstoneTest::checksMigration);
        run("RUNBOOKの各節に、その節で必要な事実を書く", OperationsCapstoneTest::checksRunbook);
        run("ADRに案と理由を書き分ける", OperationsCapstoneTest::checksAdr);

        System.out.printf("tests=%d passed=%d failed=%d%n", passed + failed, passed, failed);
        if (failed > 0) {
            throw new AssertionError(failed + "件の受け入れ条件が未達です");
        }
    }

    // ── 一覧API ──────────────────────────────────────────────

    private static final Map<Long, String> STORES =
            Map.of(31L, "さくら通り店", 32L, "港町店", 33L, "丘の上店");

    private static void listOrder() {
        CountingOrderQueryPort port = port(
                new OrderRow(9001, 31, 1200),
                new OrderRow(9003, 32, 800),
                new OrderRow(9002, 31, 450));

        List<OrderSummary> actual = new OrderListService(port).list(7);

        assertEquals(List.of(
                new OrderSummary(9003, "港町店", 800),
                new OrderSummary(9002, "さくら通り店", 450),
                new OrderSummary(9001, "さくら通り店", 1200)), actual);
    }

    private static void listBulkFetch() {
        CountingOrderQueryPort port = port(
                new OrderRow(9001, 31, 1200),
                new OrderRow(9002, 31, 450),
                new OrderRow(9003, 32, 800),
                new OrderRow(9004, 33, 990));

        new OrderListService(port).list(7);

        assertEquals(2, port.queryCount(),
                "注文1回＋店舗まとめて1回。実際に投げた問い合わせ: " + port.queries());
    }

    private static void listEmpty() {
        CountingOrderQueryPort port = port();

        assertEquals(List.of(), new OrderListService(port).list(7));
        assertEquals(1, port.queryCount(),
                "注文が0件なら店舗は引かない。実際に投げた問い合わせ: " + port.queries());
    }

    // ── 在庫解放の再試行 ───────────────────────────────────────

    private static void retryUntilSuccess() {
        RecordingInventoryClient client = new RecordingInventoryClient(2, false);
        RecordingSleeper sleeper = new RecordingSleeper();

        ReleaseResult result = new InventoryReleaseService(client, sleeper).release(9001, "key-1");

        assertTrue(result.ok(), "3回目で成功するので、成功として返す");
        assertEquals(3, result.attempts());
        assertEquals(3, client.callCount());
    }

    private static void retryBackoff() {
        RecordingInventoryClient client = new RecordingInventoryClient(2, false);
        RecordingSleeper sleeper = new RecordingSleeper();

        new InventoryReleaseService(client, sleeper).release(9001, "key-1");

        assertEquals(List.of(200L, 400L), sleeper.waits(),
                "試行の合間だけ待ち、200msから毎回2倍にする");
    }

    private static void retryGivesUp() {
        RecordingInventoryClient client = new RecordingInventoryClient(9, false);
        RecordingSleeper sleeper = new RecordingSleeper();

        ReleaseResult result = new InventoryReleaseService(client, sleeper).release(9001, "key-1");

        assertFalse(result.ok(), "3回すべて失敗したら打ち切る");
        assertEquals(3, client.callCount(), "上限を超えて下流を叩かない");
        assertEquals("inventory_timeout", result.errorCode());
        assertFalse(result.errorCode().contains("internal"), "内部のホスト名を応答へ出さない");
        assertFalse(result.errorCode().contains("Bearer"), "資格情報を応答へ出さない");
    }

    private static void permanentFailure() {
        RecordingInventoryClient client = new RecordingInventoryClient(0, true);
        RecordingSleeper sleeper = new RecordingSleeper();

        ReleaseResult result = new InventoryReleaseService(client, sleeper).release(9001, "key-1");

        assertFalse(result.ok(), "直らない失敗なので成功にはしない");
        assertEquals(1, client.callCount(), "恒久障害は再試行しない");
        assertEquals(List.of(), sleeper.waits(), "再試行しないので待たない");
        assertEquals("order_not_found", result.errorCode());
    }

    private static void idempotentRetry() {
        RecordingInventoryClient client = new RecordingInventoryClient(0, false);
        InventoryReleaseService service =
                new InventoryReleaseService(client, new RecordingSleeper());

        assertTrue(service.release(9001, "same-key").ok(), null);
        assertTrue(service.release(9001, "same-key").ok(), "再送も成功として返す");
        assertEquals(1, client.callCount(), "同じ冪等キーで下流を2回叩かない");

        assertTrue(service.release(9001, "other-key").ok(), "別の鍵は別の要求として送る");
        assertEquals(2, client.callCount());
    }

    // ── readiness ────────────────────────────────────────────

    private static void readinessUp() {
        Readiness readiness = new ReadinessProbe(List.of(
                new FixedDependencyCheck("orders-db", true),
                new FixedDependencyCheck("inventory", true))).check();

        assertTrue(readiness.ready(), "すべて正常なら受け入れ可能");
        assertEquals(Map.of("orders-db", "up", "inventory", "up"), readiness.dependencies());
    }

    private static void readinessDown() {
        Readiness readiness = new ReadinessProbe(List.of(
                new FixedDependencyCheck("orders-db", true),
                new FixedDependencyCheck("inventory", false),
                new FixedDependencyCheck("audit-store", false, true))).check();

        assertFalse(readiness.ready(), "1つでも落ちていれば受け入れ可能にしない");
        assertEquals(Map.of("orders-db", "up", "inventory", "down", "audit-store", "down"),
                readiness.dependencies());
    }

    // ── 成果物 ───────────────────────────────────────────────

    private static void checksMigration() throws Exception {
        String sql = read("db/migration/V4__add_cancel_audit.sql").toLowerCase();
        // 検査はコメントを剥がしたSQL本文へ当てる。剥がさないと、ひな形のTODOコメントに
        // 書いてある列名で contains が満たされ、逆に「NOT NULLにしない」と注意書きした
        // コメントのせいで落ちる（どちらも実際のSQLとは関係がない）。
        String code = sql.replaceAll("--[^\n]*", " ");

        assertFalse(sql.contains("todo"), "TODOを消して移行を書く");
        assertTrue(code.contains("cancel_requested_at"), "cancel_requested_at列を足す");
        assertTrue(code.contains("create table order_cancel_audit"),
                "order_cancel_auditテーブルを作る");
        assertTrue(code.contains("attempts"), "attempts列に試行回数を残す");
        assertTrue(code.contains("unique"), "order_idと冪等キーの組を一意にする");
        assertTrue(code.contains("idempotency_key"), "idempotency_key列を持たせる");
        assertFalse(code.contains("drop "), "expand段階ではDROPしない");
        assertFalse(code.matches("(?s).*cancel_requested_at[^;]*not\\s+null.*"),
                "既存行にはNULLが入るので、cancel_requested_atをNOT NULLにしない");
    }

    /**
     * 引き継ぎ文書の採点方針。
     *
     * <p>文章の巧拙や論理の妥当性は測れない。測るのは
     * <b>必要な事実が、それを必要とする節に、確認できる形で書かれているか</b>である。
     * 「事実を1箇所へまとめて並べる」「節の見出しだけ残して中身を空にする」
     * 「他の節を写す」を弾く。分量の下限は、一語で埋めるのを防ぐための最低限として置く。
     */
    private static final int MIN_SECTION_CHARS = 20;

    private static void checksRunbook() throws Exception {
        String runbook = read("RUNBOOK.md");
        assertFalse(runbook.contains("TODO"), "RUNBOOKのTODOをすべて埋める");

        Map<String, String> sections = sections(runbook);
        requireSections(sections, "RUNBOOK",
                List.of("検知", "確認", "緩和", "切り戻し", "連絡"));

        requireIn(sections, "検知", "2026-08-12T09:14:07Z",
                "最初にエラーが出た時刻を、検知の節へ書く");
        requireIn(sections, "検知", "inventory_timeout", "検知に使うエラー名を、検知の節へ書く");
        requireIn(sections, "確認", "queries=13",
                "遅くなった要求の問い合わせ回数を、確認の節へ書く");
        requireIn(sections, "確認", "orders-2.4.0", "直前の配備の版を、確認の節へ書く");
        requireIn(sections, "切り戻し", "orders-2.3.2",
                "どの版へ戻すかを、切り戻しの節へ書く（deployments.txtで確認できます）");

        // 「様子を見て戻す」では次の人が判断できない。数と単位のある目安を求める。
        assertTrue(compact(sections.get("切り戻し")).matches("(?s).*\\d+\\s*(%|ms|秒|分).*"),
                "切り戻しの目安を、数と単位のある形で書く（例: 95パーセンタイルが300msを5分以上）");
    }

    private static void checksAdr() throws Exception {
        String adr = read("ADR.md");
        assertFalse(adr.contains("TODO"), "ADRのTODOをすべて埋める");

        Map<String, String> sections = sections(adr);
        requireSections(sections, "ADR", List.of("文脈", "決定", "却下した案", "影響"));

        requireIn(sections, "文脈", "orders-2.4.0", "きっかけになった配備を、文脈の節へ書く");
        requireIn(sections, "文脈", "queries=13", "測った値を、文脈の節へ書く");
        requireIn(sections, "影響", "移行", "移行への影響を、影響の節へ書く");
        requireIn(sections, "影響", "監視", "監視への影響を、影響の節へ書く");

        // 却下した案は「なし」で埋められる。案が並んでいることと、
        // 案ごとに理由を書ける分量があることを見る（理由の中身までは測れない）。
        List<String> rejected = bullets(sections.get("却下した案"));
        assertTrue(rejected.size() >= 2,
                "却下した案を2つ以上、箇条書き（行頭の `- `）で並べる（いまは"
                        + rejected.size() + "件）");
        for (String item : rejected) {
            assertTrue(compact(item).length() >= 30,
                    "却下した案には、選ばなかった理由も書く（短すぎる項目があります: "
                            + compact(item) + "）");
        }
    }

    /** `## 見出し` で本文を切り分ける。 */
    private static Map<String, String> sections(String markdown) {
        Map<String, String> found = new java.util.LinkedHashMap<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (heading != null) {
                    found.put(heading, body.toString());
                }
                heading = line.substring(3).trim();
                body.setLength(0);
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            found.put(heading, body.toString());
        }
        return found;
    }

    private static void requireSections(
            Map<String, String> sections, String document, List<String> headings) {
        for (String heading : headings) {
            assertTrue(sections.containsKey(heading),
                    document + "に「## " + heading + "」が必要です");
            assertTrue(compact(sections.get(heading)).length() >= MIN_SECTION_CHARS,
                    document + "の「" + heading + "」の中身を書く（見出しだけでは引き継げません）");
        }
        // 節を埋めるために他の節を写していないか
        Map<String, String> seen = new java.util.LinkedHashMap<>();
        for (String heading : headings) {
            String text = compact(sections.get(heading));
            String duplicate = seen.get(text);
            assertTrue(duplicate == null,
                    document + "の「" + duplicate + "」と「" + heading + "」が同じ内容です");
            seen.put(text, heading);
        }
    }

    private static void requireIn(
            Map<String, String> sections, String heading, String needle, String message) {
        String body = sections.get(heading);
        assertTrue(body != null && body.contains(needle), message);
    }

    /** 行頭が `- ` の項目を、次の項目までを1件として取り出す。 */
    private static List<String> bullets(String body) {
        List<String> items = new java.util.ArrayList<>();
        if (body == null) {
            return items;
        }
        StringBuilder current = null;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("- ")) {
                if (current != null) {
                    items.add(current.toString());
                }
                current = new StringBuilder(line.substring(2));
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            items.add(current.toString());
        }
        return items;
    }

    /** 空白と改行を除いた本文。分量と一致の判定に使う。 */
    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    // ── 補助 ─────────────────────────────────────────────────

    private static CountingOrderQueryPort port(OrderRow... rows) {
        return new CountingOrderQueryPort(List.of(rows), STORES);
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(System.getProperty("lab.root")).resolve(relative));
    }

    private static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + " — " + e.getMessage());
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    private static void assertEquals(Object expected, Object actual, String hint) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual
                    + (hint == null ? "" : " — " + hint));
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message == null ? "条件を満たしていません" : message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
