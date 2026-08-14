import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 顧客表を読み書きするDAO。実PostgreSQLへ接続して動く。
 *
 * <p>いまの実装は、手元のテストデータでは動く。しかし実DBでは3つの問題が出る。
 * 採点はそれぞれを別に測る。
 *
 * <ol>
 *   <li>値を文字列として組み立てているので、値の中のSQLがそのまま実行される</li>
 *   <li>{@link Connection}を閉じていないので、接続がDBに残り続ける</li>
 *   <li>複数件の登録が1件ずつ確定するので、途中で失敗すると半分だけ残る</li>
 * </ol>
 *
 * <p>クラス名・コンストラクタ・メソッドの形は採点の足場が呼ぶので変えないこと。
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
        // TODO: 値を文字列に埋め込まず、プレースホルダで渡す
        // TODO: Connection・Statement・ResultSetを必ず閉じる
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        Statement statement = connection.createStatement();
        statement.execute("INSERT INTO customer(email, display_name) VALUES ('"
                + email + "', '" + displayName + "')");
    }

    /**
     * emailで顧客の表示名を探す。
     *
     * @return 見つかった表示名。無ければ {@code null}
     */
    public String findNameByEmail(String email) throws SQLException {
        // TODO: ここもプレースホルダで渡し、開いたものを閉じる
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        Statement statement = connection.createStatement();
        ResultSet found = statement.executeQuery(
                "SELECT display_name FROM customer WHERE email = '" + email + "'");
        if (found.next()) {
            return found.getString(1);
        }
        return null;
    }

    /**
     * 複数件をまとめて登録する。
     *
     * <p>1件でも失敗したら、<b>1件も登録されていない</b>状態にすること。
     *
     * @param rows {@code {email, displayName}} の2要素の配列を並べたもの
     */
    public void insertAll(List<String[]> rows) throws SQLException {
        // TODO: まとめて1つのトランザクションにする（いまは1件ずつ確定している）
        for (String[] row : rows) {
            insert(row[0], row[1]);
        }
    }
}
