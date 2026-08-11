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
        run("存在しない注文を404にする", OrderApplicationTest::returnsNotFound);
        run("他人の注文を403にする", OrderApplicationTest::returnsForbidden);
        run("支払い済み注文を409にする", OrderApplicationTest::returnsConflict);
        run("成功時に注文・outbox・監査ログを更新する", OrderApplicationTest::cancelsOrder);
        run("同じ冪等キーの再送で副作用を重ねない", OrderApplicationTest::deduplicatesRetry);
        run("保存失敗時に状態を変えず内部情報を隠す", OrderApplicationTest::rollsBackFailure);
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
        assertFalse(f.auditLog.entries().get(0).toString().contains(reason),
                "監査ログへ理由全文を入れない");
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

    private static void checksMigration() throws Exception {
        Path root = Path.of(System.getProperty("lab.root"));
        String sql = Files.readString(root.resolve("db/migration/V2__add_order_cancellation.sql"))
                .toLowerCase();
        assertTrue(sql.contains("cancel_reason"), "cancel_reason列を追加する");
        assertTrue(sql.contains("cancelled_at"), "cancelled_at列を追加する");
        assertTrue(sql.contains("create table order_outbox"), "order_outboxを作る");
        assertTrue(sql.contains("event_id") && sql.contains("primary key"),
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
