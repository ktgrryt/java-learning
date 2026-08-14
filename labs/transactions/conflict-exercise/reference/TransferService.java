import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 口座間の送金（模範解答）。
 *
 * <p>要点は5つ。
 *
 * <ul>
 *   <li><b>1件の送金＝1つのトランザクション。</b>autocommitを切り、最後にcommitする。
 *       途中で失敗したらrollbackするので、half-doneな状態が残らない。</li>
 *   <li><b>金額の計算はDBに任せる。</b>{@code SET balance = balance - ?}なら、
 *       読んで計算して書く間に他の送金が入り込む隙がない。Javaで計算して書き戻すと、
 *       同時に動いた送金の結果を上書きしてしまう。</li>
 *   <li><b>更新できた行数を確かめる。</b>存在しない口座への{@code UPDATE}は
 *       エラーにならず「0行更新」で成功する。数えないと、お金が消えたことに気づけない。</li>
 *   <li><b>ロックの取得順をそろえる。</b>口座IDの小さい方から更新すれば、
 *       A→BとB→Aが同時に来ても輪にならない。順序をそろえないなら、
 *       DBが返す衝突（40001 / 40P01）を捕まえて再試行する必要がある。</li>
 *   <li><b>2回目は何もしない。</b>送金IDはPRIMARY KEYなので、2回目のINSERTは必ず衝突する。
 *       これを「すでに済んでいる」と読み替えるのが冪等性。記録を<b>先に</b>入れるのが要点で、
 *       残高を動かしてから記録すると、2回目に残高だけ二重に動く。</li>
 * </ul>
 *
 * <p>ここでは順序をそろえた上で、念のため再試行も入れてある（別の経路から
 * 逆順で触られても耐えるように）。
 */
public class TransferService {

    /** 一意制約違反。PostgreSQLのSQLState。 */
    private static final String UNIQUE_VIOLATION = "23505";

    /** 直列化失敗とデッドロック。どちらも「やり直せば通る」種類の失敗。 */
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private static final int MAX_ATTEMPTS = 5;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public TransferService(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /**
     * {@code from}から{@code to}へ{@code amount}を移し、送金を{@code transfer}表へ記録する。
     *
     * @throws SQLException 送金できなかったとき
     */
    public void transfer(String transferId, String from, String to, int amount)
            throws SQLException {
        SQLException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transferOnce(transferId, from, to, amount);
                return;
            } catch (SQLException conflict) {
                String state = conflict.getSQLState();
                if (!SERIALIZATION_FAILURE.equals(state) && !DEADLOCK_DETECTED.equals(state)) {
                    throw conflict;
                }
                // やり直せば通る失敗。少し待ってから同じ送金IDでもう一度
                lastConflict = conflict;
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw conflict;
                }
            }
        }
        throw lastConflict;
    }

    private void transferOnce(String transferId, String from, String to, int amount)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            connection.setAutoCommit(false);
            try {
                // 記録を先に入れる。同じ送金IDの2回目はここで弾かれるので、残高は動かない
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO transfer(id, from_account, to_account, amount)"
                                + " VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, transferId);
                    insert.setString(2, from);
                    insert.setString(3, to);
                    insert.setInt(4, amount);
                    insert.executeUpdate();
                } catch (SQLException duplicate) {
                    if (UNIQUE_VIOLATION.equals(duplicate.getSQLState())) {
                        connection.rollback();   // すでに済んでいる送金。何もしない
                        return;
                    }
                    throw duplicate;
                }

                // 口座IDの小さい方から先に更新する（取得順をそろえて輪を作らない）
                if (from.compareTo(to) < 0) {
                    add(connection, from, -amount);
                    add(connection, to, amount);
                } else {
                    add(connection, to, amount);
                    add(connection, from, -amount);
                }

                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();   // 途中で失敗したら、何も残さない
                throw failed;
            }
        }
    }

    /** 1つの口座の残高を動かす。計算はDBに任せ、更新できた行数を必ず確かめる。 */
    private static void add(Connection connection, String account, int delta) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE account SET balance = balance + ? WHERE id = ?")) {
            update.setInt(1, delta);
            update.setString(2, account);
            if (update.executeUpdate() != 1) {
                // 存在しない口座でもエラーにはならない。0行更新を失敗として扱う
                throw new SQLException("口座がありません: " + account);
            }
        }
    }
}
