import cafe.jpa.Customer;
import cafe.jpa.CustomerRepository;
import cafe.jpa.CustomerSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Persistence;
import jakarta.persistence.RollbackException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * {@link CustomerRepository} と{@link Customer}のマッピングを、実PostgreSQLへ当てて測る足場。参照専用。
 *
 * <p>N+1は「動くけれど遅い」だけなので、出力を見ても分からない。ここではHibernateの統計から
 * <b>発行されたSQLの本数</b>を数える。閉じたあとの遅延読み込みと同時更新は、例外の有無で見る。
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class JpaHarness {

    private static final int CUSTOMERS = 5;
    private static final int ORDERS_PER_CUSTOMER = 3;

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String user = args[1];
        String password = args[2];
        Path schema = Path.of(args[3]);

        applySchema(url, user, password, schema);

        EntityManagerFactory factory = Persistence.createEntityManagerFactory("cafe", Map.of(
                "jakarta.persistence.jdbc.url", url,
                "jakarta.persistence.jdbc.user", user,
                "jakarta.persistence.jdbc.password", password));
        try {
            long firstId = seed(factory);
            Statistics statistics = factory.unwrap(SessionFactory.class).getStatistics();
            CustomerRepository repository = new CustomerRepository(factory);

            // ── 1. 関連をたどるのに何本のSQLを使ったか ──────────────────────
            statistics.clear();
            try {
                List<CustomerSummary> all = repository.findAllWithOrders();
                long statements = statistics.getPrepareStatementCount();
                print("list-statements", String.valueOf(statements));
                print("list-size", String.valueOf(all.size()));
                print("list-items", String.valueOf(all.isEmpty() ? -1 : all.get(0).items().size()));
            } catch (Exception failed) {
                print("list-error", describe(failed));
            }

            // ── 2. 閉じたあとでも使える値が返るか ────────────────────────────
            try {
                CustomerSummary one = repository.findOne(firstId);
                print("one-name", one == null ? "null" : one.name());
                print("one-items", String.valueOf(one == null ? -1 : one.items().size()));
            } catch (Exception failed) {
                print("one-error", describe(failed));
            }

            // ── 3. 同時更新に気づけるか ──────────────────────────────────
            print("conflict", conflictOutcome(factory, firstId));
            print("conflict-budget", String.valueOf(budget(url, user, password, firstId)));
        } finally {
            factory.close();
        }
    }

    /**
     * 同じ行を2つのEntityManagerで読み、両方が書く。
     *
     * @return {@code detected}（あとの更新が弾かれた）／{@code overwritten}（黙って上書きした）
     */
    private static String conflictOutcome(EntityManagerFactory factory, long id) {
        EntityManager first = factory.createEntityManager();
        EntityManager second = factory.createEntityManager();
        try {
            first.getTransaction().begin();
            Customer byFirst = first.find(Customer.class, id);
            second.getTransaction().begin();
            Customer bySecond = second.find(Customer.class, id);

            byFirst.setBudgetYen(1_000);
            first.getTransaction().commit();

            bySecond.setBudgetYen(2_000);
            try {
                second.getTransaction().commit();
                return "overwritten";
            } catch (OptimisticLockException | RollbackException conflict) {
                Throwable cause = conflict;
                while (cause != null) {
                    if (cause instanceof OptimisticLockException) return "detected";
                    cause = cause.getCause();
                }
                return "other:" + describe(conflict);
            }
        } catch (Exception failed) {
            return "error:" + describe(failed);
        } finally {
            close(first);
            close(second);
        }
    }

    private static void close(EntityManager manager) {
        if (manager.getTransaction().isActive()) {
            try {
                manager.getTransaction().rollback();
            } catch (Exception ignored) {
                // 片付けの失敗は結果に影響しない
            }
        }
        manager.close();
    }

    /** 顧客5人と、1人あたり3件の注文を入れる。 */
    private static long seed(EntityManagerFactory factory) {
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            long firstId = -1;
            for (int i = 1; i <= CUSTOMERS; i++) {
                Customer customer = new Customer("customer-" + i, 5_000);
                for (int n = 1; n <= ORDERS_PER_CUSTOMER; n++) {
                    customer.addOrder("item-" + i + "-" + n);
                }
                manager.persist(customer);
                manager.flush();
                if (firstId < 0) firstId = customer.getId();
            }
            manager.getTransaction().commit();
            return firstId;
        } finally {
            manager.close();
        }
    }

    private static void applySchema(String url, String user, String password, Path schema)
            throws Exception {
        String ddl = Files.readString(schema);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static int budget(String url, String user, String password, long id) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var found = statement.executeQuery(
                     "SELECT budget_yen FROM customer WHERE id = " + id)) {
            return found.next() ? found.getInt(1) : -1;
        } catch (Exception failed) {
            return -1;
        }
    }

    private static String describe(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (!text.isEmpty()) text.append(" <- ");
            text.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                String message = current.getMessage().replaceAll("[\\r\\n\\t]", " ");
                text.append(':').append(message.length() > 80
                        ? message.substring(0, 80) + "..." : message);
            }
            if (current.getCause() == current) break;
        }
        return text.toString();
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t"
                + String.valueOf(value).replace('\t', ' ').replace('\n', ' '));
    }
}
