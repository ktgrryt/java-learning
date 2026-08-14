import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link CustomerDao} を実PostgreSQLへ当てて、後始末まで測る足場。参照専用。
 *
 * <p>接続の閉じ忘れは、コードを読んでも見落とす。ここでは実DBの
 * {@code pg_stat_activity}（いま繋がっている接続の一覧）を見て数える。
 * DAOには{@code ApplicationName=jq-dao}を付けたURLを渡すので、
 * 足場自身の接続と区別できる。
 *
 * <p>結果は {@code RESULT<TAB>項目<TAB>値} で出す。PASS / FAIL の判定は run-runtime-lab.sh が行う。
 */
public final class JdbcHarness {

    /** DAOに渡すURLへ付ける印。実DB側から「DAOの接続」だけを数えるために使う。 */
    private static final String DAO_TAG = "jq-dao";

    /** 接続の枠（max_connections=10）を、漏らしていれば必ず使い切る回数。 */
    private static final int CALLS_PER_LEAK_CHECK = 60;

    private static String baseUrl;
    private static String user;
    private static String password;
    private static Path schema;

    public static void main(String[] args) throws Exception {
        baseUrl = args[0];
        user = args[1];
        password = args[2];
        schema = Path.of(args[3]);

        // 場面ごとに囲む。接続を漏らす実装では枠が尽きて足場側の接続も取れなくなるので、
        // 1つの場面の失敗で測定全体を落とさない
        scene("crud", JdbcHarness::crud);
        scene("injection", JdbcHarness::injection);
        scene("rollback", JdbcHarness::rollback);
        // 接続を漏らす実装は、ここで接続の枠を使い切る。あとの場面を巻き込まないよう最後に回す
        scene("leak", JdbcHarness::leak);

        // 測定は終わり。ここまでの print が結果になる
        System.out.flush();
        System.exit(0);
    }

    /** 1つの場面を流す。中で落ちても、その場面だけを失敗として記録する。 */
    private static void scene(String name, Scene body) {
        try {
            body.run();
        } catch (Throwable failed) {
            print(name + "-fatal", failed.getClass().getSimpleName() + ":" + failed.getMessage());
        }
    }

    private interface Scene {
        void run() throws Exception;
    }

    /** 登録して読み戻せるか。 */
    private static void crud() throws Exception {
        reset();
        CustomerDao dao = dao();
        try {
            dao.insert("a@example.test", "Ada");
            print("crud-found", String.valueOf(dao.findNameByEmail("a@example.test")));
            print("crud-missing", String.valueOf(dao.findNameByEmail("nobody@example.test")));
            print("crud-rows", String.valueOf(countCustomers()));
        } catch (Exception failed) {
            print("crud-error", failed.getClass().getSimpleName() + ":" + failed.getMessage());
        }
    }

    /**
     * 値の中のSQLが実行されてしまわないか。
     *
     * <p>表示名として {@code x'); DROP TABLE customer; --} を渡す。値を文字列に埋め込んで
     * いれば、INSERTのあとにDROP TABLEが続く1本の命令になり、表そのものが消える。
     */
    private static void injection() throws Exception {
        reset();
        CustomerDao dao = dao();
        String attack = "x'); DROP TABLE customer; --";
        try {
            dao.insert("evil@example.test", attack);
            print("injection-insert", "ok");
        } catch (Exception failed) {
            print("injection-insert", "threw:" + failed.getMessage());
        }
        print("injection-table-exists", String.valueOf(tableExists()));
        String stored = null;
        try {
            stored = dao.findNameByEmail("evil@example.test");
        } catch (Exception ignored) {
            // 表が消えていれば読めない。table-exists 側で分かる
        }
        print("injection-stored", String.valueOf(stored));
    }

    /**
     * 接続を閉じているか。
     *
     * <p>「いま何本開いているか」を数える方法では測れない。参照が切れた接続はGCで回収され、
     * ソケットも閉じられるので、数える時点までに消えていることがある（実測した）。
     *
     * <p>そこで、閉じ忘れが本番で表に出るのと同じ形——<b>接続の枠を使い切る</b>——で測る。
     * このlabのPostgreSQLは{@code max_connections=10}で動いている。閉じていれば
     * 何度呼んでも1〜2本しか使わないが、漏らしていれば途中で
     * {@code too many clients already}になる。
     */
    private static void leak() throws Exception {
        reset();
        CustomerDao dao = dao();
        int failures = 0;
        String firstError = "";
        for (int i = 0; i < CALLS_PER_LEAK_CHECK; i++) {
            try {
                dao.insert("leak" + i + "@example.test", "Leak " + i);
                dao.findNameByEmail("leak" + i + "@example.test");
            } catch (Exception failed) {
                failures++;
                if (firstError.isEmpty()) {
                    firstError = "i=" + i + " " + failed.getClass().getSimpleName()
                            + ":" + failed.getMessage();
                }
            }
        }
        print("leak-calls", String.valueOf(CALLS_PER_LEAK_CHECK));
        print("leak-failures", String.valueOf(failures));
        print("leak-first-error", firstError);
        // 参考値。GCの回収と重なるので、これだけでは判定に使わない
        print("leak-open-connections", String.valueOf(daoConnections()));
    }

    /** 途中で失敗したまとめ登録が、1件も残さないか。 */
    private static void rollback() throws Exception {
        reset();
        CustomerDao dao = dao();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "first@example.test", "First" });
        rows.add(new String[] { "dup@example.test", "Dup" });
        rows.add(new String[] { "dup@example.test", "Dup again" });   // 主キー違反
        rows.add(new String[] { "last@example.test", "Last" });
        String outcome;
        try {
            dao.insertAll(rows);
            outcome = "returned";
        } catch (Exception expected) {
            outcome = "threw";
        }
        print("rollback-outcome", outcome);
        print("rollback-rows", String.valueOf(countCustomers()));
    }

    // ---- DBを読む・作り直す（採点側の道具） ----------------------------------

    private static CustomerDao dao() {
        return new CustomerDao(baseUrl + "?ApplicationName=" + DAO_TAG, user, password);
    }

    /** 足場自身の接続。DAOのものと混ざらないよう別の名前を付ける。 */
    private static Connection open() throws SQLException {
        return DriverManager.getConnection(baseUrl + "?ApplicationName=jq-harness", user, password);
    }

    private static void reset() throws Exception {
        String ddl = Files.readString(schema);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static int countCustomers() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet found = statement.executeQuery("SELECT count(*) FROM customer")) {
            return found.next() ? found.getInt(1) : -1;
        }
    }

    private static boolean tableExists() throws SQLException {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM information_schema.tables"
                             + " WHERE table_schema = 'public' AND table_name = 'customer'")) {
            try (ResultSet found = select.executeQuery()) {
                return found.next() && found.getInt(1) == 1;
            }
        }
    }

    /**
     * いま繋がっているDAOの接続の数を、実DBに数えさせる。
     *
     * <p>参考値なので、枠が尽きて自分も繋げないときは{@code -1}にして先へ進む。
     */
    private static int daoConnections() {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")) {
            select.setString(1, DAO_TAG);
            try (ResultSet found = select.executeQuery()) {
                return found.next() ? found.getInt(1) : -1;
            }
        } catch (SQLException starved) {
            return -1;
        }
    }

    private static void print(String key, String value) {
        System.out.println("RESULT\t" + key + "\t"
                + String.valueOf(value).replace('\t', ' ').replace('\n', ' '));
    }
}
