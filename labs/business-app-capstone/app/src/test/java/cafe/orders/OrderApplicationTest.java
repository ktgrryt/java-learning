package cafe.orders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class OrderApplicationTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("理由を正規化する", OrderApplicationTest::normalizesReason);
        run("空の理由を400にする", OrderApplicationTest::rejectsBlankReason);
        run("101文字の理由を400にする", OrderApplicationTest::rejectsLongReason);
        run("100文字ちょうどの理由は受け付ける", OrderApplicationTest::acceptsHundredCharacters);
        run("長さは前後の空白を除いてから数える", OrderApplicationTest::measuresLengthAfterStrip);
        run("空の冪等キーを400にする", OrderApplicationTest::rejectsBlankIdempotencyKey);
        run("入力の検査を注文の存在より先に行う", OrderApplicationTest::checksInputBeforeExistence);
        run("所有者の検査を状態より先に行う", OrderApplicationTest::checksOwnerBeforeState);
        run("存在しない注文を404にする", OrderApplicationTest::returnsNotFound);
        run("他人の注文を403にする", OrderApplicationTest::returnsForbidden);
        run("支払い済み注文を409にする", OrderApplicationTest::returnsConflict);
        run("成功時に注文・outbox・監査ログを更新する", OrderApplicationTest::cancelsOrder);
        run("同じ冪等キーの再送で副作用を重ねない", OrderApplicationTest::deduplicatesRetry);
        run("保存失敗時に状態を変えず内部情報を隠す", OrderApplicationTest::rollsBackFailure);
        run("保存に失敗した依頼を処理済みにしない", OrderApplicationTest::doesNotRememberFailedRequest);
        run("expand用のDB移行を用意する", OrderApplicationTest::checksMigration);
        run("PRに検証・配備・監視・切り戻しを残す", OrderApplicationTest::checksPullRequest);

        System.out.printf("tests=%d passed=%d failed=%d%n", passed + failed, passed, failed);
        if (failed > 0) {
            throw new AssertionError(failed + "件の受け入れ条件が未達です");
        }
    }

    private static void normalizesReason() {
        CancelOrderRequest request = request("  customer request  ", "key-1");
        request.validate();
        assertEquals("customer request", request.normalizedReason());
    }

    private static void rejectsBlankReason() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        assertEquals(new ApiResponse(400, "invalid_request"),
                f.controller.cancel(10, request("   ", "key-1")));
        assertEquals(0, f.repository.saveCount());
    }

    private static void rejectsLongReason() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        assertEquals(new ApiResponse(400, "invalid_request"),
                f.controller.cancel(10, request("a".repeat(101), "key-1")));
        assertEquals(0, f.repository.saveCount());
    }

    private static void acceptsHundredCharacters() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        assertEquals(new ApiResponse(204, ""),
                f.controller.cancel(10, request("a".repeat(100), "key-1")));
        assertEquals(1, f.repository.saveCount());
    }

    private static void measuresLengthAfterStrip() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        assertEquals(new ApiResponse(204, ""),
                f.controller.cancel(10, request("  " + "a".repeat(100) + "  ", "key-1")));
        assertEquals("a".repeat(100), f.repository.current().orElseThrow().cancelReason());
    }

    private static void rejectsBlankIdempotencyKey() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        assertEquals(new ApiResponse(400, "invalid_request"),
                f.controller.cancel(10, request("customer request", "  ")));
        assertEquals(0, f.repository.saveCount());
    }

    private static void checksInputBeforeExistence() {
        Fixture f = fixture(null, false);
        assertEquals(new ApiResponse(400, "invalid_request"),
                f.controller.cancel(99, request("   ", "key-1")));
    }

    private static void checksOwnerBeforeState() {
        Order othersPaid = new Order(10, 8, OrderStatus.PAID, null, null, 3);
        Fixture f = fixture(othersPaid, false);
        assertEquals(new ApiResponse(403, "forbidden"),
                f.controller.cancel(10, request("customer request", "key-1")));
    }

    private static void returnsNotFound() {
        Fixture f = fixture(null, false);
        assertEquals(new ApiResponse(404, "order_not_found"),
                f.controller.cancel(99, request("customer request", "key-1")));
    }

    private static void returnsForbidden() {
        Fixture f = fixture(Order.newOrder(10, 8), false);
        assertEquals(new ApiResponse(403, "forbidden"),
                f.controller.cancel(10, request("customer request", "key-1")));
        assertEquals(OrderStatus.NEW, f.repository.current().orElseThrow().status());
    }

    private static void returnsConflict() {
        Order paid = new Order(10, 7, OrderStatus.PAID, null, null, 3);
        Fixture f = fixture(paid, false);
        assertEquals(new ApiResponse(409, "order_not_cancellable"),
                f.controller.cancel(10, request("customer request", "key-1")));
        assertEquals(0, f.repository.saveCount());
    }

    private static void cancelsOrder() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        String reason = "secret-customer-note";
        ApiResponse response = f.controller.cancel(10, request("  " + reason + "  ", "key-1"));

        assertEquals(new ApiResponse(204, ""), response);
        Order saved = f.repository.current().orElseThrow();
        assertEquals(OrderStatus.CANCELLED, saved.status());
        assertEquals(reason, saved.cancelReason());
        assertEquals(NOW, saved.cancelledAt());
        assertEquals(2L, saved.version());
        assertEquals(1, f.repository.saveCount());
        assertEquals(1, f.repository.outbox().size());
        assertEquals("OrderCancelled", f.repository.outbox().get(0).eventType());
        assertEquals(1, f.auditLog.entries().size());
        RecordingAuditLog.Entry entry = f.auditLog.entries().get(0);
        assertEquals("request-123", entry.requestId());
        assertEquals(10L, entry.orderId());
        assertEquals(7L, entry.actorId());
        assertTrue(entry.result() != null && !entry.result().isBlank(), "監査ログへ結果を記録する");
        assertFalse(entry.toString().contains(reason), "監査ログへ理由全文を入れない");
    }

    private static void deduplicatesRetry() {
        Fixture f = fixture(Order.newOrder(10, 7), false);
        CancelOrderRequest request = request("customer request", "same-key");

        assertEquals(new ApiResponse(204, ""), f.controller.cancel(10, request));
        assertEquals(new ApiResponse(204, ""), f.controller.cancel(10, request));
        assertEquals(1, f.repository.saveCount());
        assertEquals(1, f.repository.outbox().size());
        assertEquals(1, f.auditLog.entries().size());
    }

    private static void rollsBackFailure() {
        Fixture f = fixture(Order.newOrder(10, 7), true);
        ApiResponse response = f.controller.cancel(10, request("customer request", "key-1"));

        assertEquals(new ApiResponse(500, "internal_error"), response);
        assertEquals(OrderStatus.NEW, f.repository.current().orElseThrow().status());
        assertEquals(0, f.repository.saveCount());
        assertEquals(0, f.repository.outbox().size());
        assertEquals(0, f.auditLog.entries().size());
        assertFalse(response.errorCode().contains("secret"), "内部の秘密値を応答へ出さない");
    }

    private static void doesNotRememberFailedRequest() {
        Fixture f = fixture(Order.newOrder(10, 7), true);
        CancelOrderRequest request = request("customer request", "same-key");

        assertEquals(new ApiResponse(500, "internal_error"), f.controller.cancel(10, request));
        // 失敗した依頼を処理済みにすると、再送が「成功」を返したまま何も保存されない
        assertEquals(new ApiResponse(500, "internal_error"), f.controller.cancel(10, request));
        assertEquals(OrderStatus.NEW, f.repository.current().orElseThrow().status());
        assertEquals(0, f.auditLog.entries().size());
    }

    private static void checksMigration() throws Exception {
        Path root = Path.of(System.getProperty("lab.root"));
        // ひな形のTODOコメントにも列名が出てくるので、コメントを除いてから調べる
        String sql = Files.readString(root.resolve("db/migration/V2__add_order_cancellation.sql"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "")
                .toLowerCase().replaceAll("\\s+", " ");
        assertTrue(sql.contains("alter table orders"), "既存のordersへ列を足す（alter table orders）");
        assertTrue(sql.contains("cancel_reason"), "cancel_reason列を追加する");
        assertTrue(sql.contains("cancelled_at"), "cancelled_at列を追加する");
        assertTrue(sql.contains("create table order_outbox"), "order_outboxを作る");
        assertTrue(sql.matches("(?s).*event_id[^,;]*primary key.*")
                        || sql.matches("(?s).*primary key \\( ?event_id ?\\).*"),
                "event_idを主キーにする");
        assertFalse(sql.matches("(?s).*cancel_reason[^;]*not\\s+null.*"),
                "expand段階ではcancel_reasonをNULL許可にする");
        assertFalse(sql.matches("(?s).*cancelled_at[^;]*not\\s+null.*"),
                "expand段階ではcancelled_atをNULL許可にする");
    }

    private static void checksPullRequest() throws Exception {
        Path root = Path.of(System.getProperty("lab.root"));
        String pr = Files.readString(root.resolve("PR.md"));
        assertFalse(pr.contains("TODO"), "PRのTODOをすべて埋める");
        assertTrue(pr.contains("./run-tests.sh"), "実行したテストコマンドを記録する");
        assertTrue(pr.contains("V2__add_order_cancellation.sql"), "DB移行への影響を記録する");
        assertTrue(pr.contains("order_cancelled"), "監視するイベントを記録する");
        assertTrue(pr.contains("切り戻し"), "切り戻し方針を記録する");
        // 語が並んでいるだけの報告を通さない。見出しごとに本文があり、同じ文の写しでないこと
        java.util.Map<String, String> sections = new java.util.LinkedHashMap<>();
        String heading = "冒頭";
        StringBuilder body = new StringBuilder();
        for (String line : pr.split("\\R")) {
            if (line.startsWith("#")) {
                if (!heading.equals("冒頭") || body.length() > 0) sections.put(heading, body.toString().strip());
                heading = line.replaceFirst("^#+\\s*", "");
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        sections.put(heading, body.toString().strip());
        for (String required : java.util.List.of("API・DBへの影響", "検証", "配備と切り戻し", "監視")) {
            assertTrue(sections.containsKey(required), "PRに「## " + required + "」の節を残す");
        }
        for (var section : sections.entrySet()) {
            assertTrue(section.getValue().replaceAll("\\s", "").length() >= 20,
                    "PRの「" + section.getKey() + "」に、20文字以上の本文を書く");
        }
        assertTrue(new java.util.HashSet<>(sections.values()).size() == sections.size(),
                "PRの各節へ同じ文を写さず、節ごとの内容を書く");
    }

    private static Fixture fixture(Order order, boolean failCommit) {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(order, failCommit);
        RecordingAuditLog auditLog = new RecordingAuditLog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrderService service = new OrderService(repository, auditLog, clock);
        return new Fixture(new OrderController(service), repository, auditLog);
    }

    private static CancelOrderRequest request(String reason, String key) {
        return new CancelOrderRequest(7, reason, key, "request-123");
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
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private record Fixture(
            OrderController controller,
            InMemoryOrderRepository repository,
            RecordingAuditLog auditLog) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
