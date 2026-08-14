import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link TransferService} を実PostgreSQLへ当てて、トランザクションの効き方を測る足場。参照専用。
 *
 * <p>4つの場面を順番に流し、そのたびにDBを作り直す。結果は必ず<b>DBを読んで</b>確かめる
 * （serviceの戻り値や例外だけを見ても、実際に何が残ったかは分からない）。
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class TxHarness {

    private static final int START_BALANCE = 100_000;
    /** 同時送金の回数（1スレッドあたり）。 */
    private static final int CONCURRENT_TRANSFERS = 300;

    private static String jdbcUrl;
    private static String user;
    private static String password;
    private static Path schema;

    public static void main(String[] args) throws Exception {
        jdbcUrl = args[0];
        user = args[1];
        password = args[2];
        schema = Path.of(args[3]);

        rollback();
        lostUpdate();
        crossing();
        idempotent();
    }

    /** 途中で失敗した送金が、DBに何も残さないか。 */
    private static void rollback() throws Exception {
        reset();
        TransferService service = service();
        String outcome;
        try {
            // 送金先が存在しない。UPDATEは0行更新で成功してしまうので、
            // 行数を数えていないと「送金元だけ減る」ことになる
            service.transfer("T-broken", "A", "GHOST", 500);
            outcome = "returned";
        } catch (Exception expected) {
            outcome = "threw";
        }
        print("rollback-outcome", outcome);
        print("rollback-balance-a", String.valueOf(balance("A")));
        print("rollback-transfers", String.valueOf(countTransfers()));
    }

    /** 同じ向きの同時送金で、更新が失われないか。 */
    private static void lostUpdate() throws Exception {
        reset();
        TransferService service = service();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>("");
        CyclicBarrier start = new CyclicBarrier(2);

        Thread[] senders = new Thread[2];
        for (int t = 0; t < 2; t++) {
            String prefix = "L" + t + "-";
            senders[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (Exception stop) {
                    return;
                }
                for (int n = 0; n < CONCURRENT_TRANSFERS; n++) {
                    try {
                        service.transfer(prefix + n, "A", "B", 1);
                    } catch (Exception failed) {
                        failures.incrementAndGet();
                        firstError.compareAndSet("", failed.getClass().getSimpleName()
                                + ":" + failed.getMessage());
                    }
                }
            }, "sender-" + t);
            senders[t].start();
        }
        for (Thread sender : senders) sender.join();

        print("lost-failures", String.valueOf(failures.get()));
        print("lost-first-error", firstError.get());
        print("lost-balance-a", String.valueOf(balance("A")));
        print("lost-balance-b", String.valueOf(balance("B")));
        print("lost-transfers", String.valueOf(countTransfers()));
        print("lost-expected-a", String.valueOf(START_BALANCE - 2 * CONCURRENT_TRANSFERS));
        print("lost-expected-b", String.valueOf(START_BALANCE + 2 * CONCURRENT_TRANSFERS));
        print("lost-expected-transfers", String.valueOf(2 * CONCURRENT_TRANSFERS));
    }

    /** 逆向きの同時送金で、待ち合わせに巻き込まれず全部反映されるか。 */
    private static void crossing() throws Exception {
        reset();
        TransferService service = service();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>("");
        CyclicBarrier start = new CyclicBarrier(2);

        Thread forward = crossingThread(service, "A", "B", "F-", start, failures, firstError);
        Thread backward = crossingThread(service, "B", "A", "R-", start, failures, firstError);
        forward.start();
        backward.start();
        forward.join();
        backward.join();

        print("crossing-failures", String.valueOf(failures.get()));
        print("crossing-first-error", firstError.get());
        print("crossing-balance-a", String.valueOf(balance("A")));
        print("crossing-balance-b", String.valueOf(balance("B")));
        print("crossing-transfers", String.valueOf(countTransfers()));
        print("crossing-expected-transfers", String.valueOf(2 * CONCURRENT_TRANSFERS));
        print("crossing-expected-balance", String.valueOf(START_BALANCE));
    }

    private static Thread crossingThread(TransferService service, String from, String to,
                                         String prefix, CyclicBarrier start,
                                         AtomicInteger failures, AtomicReference<String> firstError) {
        return new Thread(() -> {
            try {
                start.await();
            } catch (Exception stop) {
                return;
            }
            for (int n = 0; n < CONCURRENT_TRANSFERS; n++) {
                try {
                    service.transfer(prefix + n, from, to, 1);
                } catch (Exception failed) {
                    failures.incrementAndGet();
                    firstError.compareAndSet("", failed.getClass().getSimpleName()
                            + ":" + failed.getMessage());
                }
            }
        }, "crossing-" + prefix);
    }

    /** 同じ送金IDが2回届いても、1回だけ反映されるか。 */
    private static void idempotent() throws Exception {
        reset();
        TransferService service = service();
        String first;
        String second;
        try {
            service.transfer("T-100", "A", "B", 700);
            first = "ok";
        } catch (Exception failed) {
            first = "threw:" + failed.getMessage();
        }
        try {
            service.transfer("T-100", "A", "B", 700);
            second = "ok";
        } catch (Exception failed) {
            second = "threw:" + failed.getMessage();
        }
        print("idempotent-first", first);
        print("idempotent-second", second);
        print("idempotent-balance-a", String.valueOf(balance("A")));
        print("idempotent-balance-b", String.valueOf(balance("B")));
        print("idempotent-transfers", String.valueOf(countTransfers()));
        print("idempotent-expected-a", String.valueOf(START_BALANCE - 700));
        print("idempotent-expected-b", String.valueOf(START_BALANCE + 700));
    }

    // ---- DBを読む・作り直す（採点側の道具） ----------------------------------

    private static TransferService service() {
        return new TransferService(jdbcUrl, user, password);
    }

    /** 表を作り直し、口座を2つ入れる。場面ごとに同じ状態から始める。 */
    private static void reset() throws Exception {
        String ddl = Files.readString(schema);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO account(id, balance) VALUES (?, ?)")) {
                for (String id : new String[] { "A", "B" }) {
                    insert.setString(1, id);
                    insert.setInt(2, START_BALANCE);
                    insert.executeUpdate();
                }
            }
        }
    }

    private static int balance(String account) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement select =
                     connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
            select.setString(1, account);
            try (ResultSet found = select.executeQuery()) {
                return found.next() ? found.getInt(1) : -1;
            }
        }
    }

    private static int countTransfers() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement();
             ResultSet found = statement.executeQuery("SELECT count(*) FROM transfer")) {
            return found.next() ? found.getInt(1) : -1;
        }
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t"
                + String.valueOf(value).replace('\t', ' ').replace('\n', ' '));
    }
}
