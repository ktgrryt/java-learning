import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 顧客表を読み書きするDAO（模範解答）。
 *
 * <p>要点は3つ。
 *
 * <ul>
 *   <li><b>値は文字列に埋め込まず、プレースホルダで渡す。</b>
 *       {@link PreparedStatement}なら、値の中に何が入っていてもSQLとしては解釈されない。
 *       エスケープを自分で書くのは、抜けたときに気づけないので解決にならない。</li>
 *   <li><b>開いたものは必ず閉じる。</b>try-with-resourcesなら、途中で例外が出ても閉じられる。
 *       閉じ忘れた接続はDBに残り続け、いつか上限に当たって「原因不明の接続エラー」になる。</li>
 *   <li><b>まとめて成功・まとめて失敗にする。</b>autocommitのままだと1件ずつ確定するので、
 *       途中で失敗すると半分だけ登録された状態が残る。</li>
 * </ul>
 */
public class CustomerDao {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public CustomerDao(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /** 顧客を1件登録する。 */
    public void insert(String email, String displayName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            insertOn(connection, email, displayName);
        }
    }

    /**
     * emailで顧客の表示名を探す。
     *
     * @return 見つかった表示名。無ければ {@code null}
     */
    public String findNameByEmail(String email) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT display_name FROM customer WHERE email = ?")) {
            select.setString(1, email);
            try (ResultSet found = select.executeQuery()) {
                return found.next() ? found.getString(1) : null;
            }
        }
    }

    /**
     * 複数件をまとめて登録する。1件でも失敗したら1件も登録しない。
     *
     * @param rows {@code {email, displayName}} の2要素の配列を並べたもの
     */
    public void insertAll(List<String[]> rows) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            connection.setAutoCommit(false);
            try {
                for (String[] row : rows) {
                    insertOn(connection, row[0], row[1]);
                }
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                throw failed;
            }
        }
    }

    /** 同じ接続の上で1件登録する（トランザクションを分けないため、接続を引数で受ける）。 */
    private static void insertOn(Connection connection, String email, String displayName)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO customer(email, display_name) VALUES (?, ?)")) {
            insert.setString(1, email);
            insert.setString(2, displayName);
            insert.executeUpdate();
        }
    }
}
