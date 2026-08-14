import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 口座間の送金。実PostgreSQLへ接続して動く。
 *
 * <p>いまの実装は、1件ずつ別々に流している（autocommitのまま）。1スレッドで成功する分には
 * 動いて見えるが、実DBでは4つの問題が出る。採点はそれぞれを別に測る。
 *
 * <ol>
 *   <li>途中で失敗すると、そこまでの変更が残る（お金が消える）</li>
 *   <li>同時に送金すると、読んで計算して書く間に他の送金が入り、更新が失われる</li>
 *   <li>逆向きの送金が同時に来ると、待ち合わせで失敗する</li>
 *   <li>同じ送金IDが2回届くと、2回反映される</li>
 * </ol>
 *
 * <p>クラス名・コンストラクタ・{@link #transfer}の形は採点の足場が呼ぶので変えないこと。
 */
public class TransferService {

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
     * <p>満たすこと:
     *
     * <ul>
     *   <li>成功したときだけ、残高2件と記録1件が<b>すべて</b>反映される。
     *       途中で失敗したら<b>何も</b>残さない</li>
     *   <li>同じ{@code transferId}で2回呼ばれたら、2回目は何もしない（例外も投げない）</li>
     *   <li>存在しない口座、残高不足、金額0以下は失敗させる</li>
     * </ul>
     *
     * @throws SQLException 送金できなかったとき
     */
    public void transfer(String transferId, String from, String to, int amount)
            throws SQLException {
        // TODO: 1件の送金を1つのトランザクションにまとめる（autocommitを切る）
        // TODO: 同じtransferIdの2回目は、何もせずに戻る
        // TODO: 更新できた行数を確かめる（存在しない口座でも0行更新はエラーにならない）
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            int balance;
            try (PreparedStatement select =
                         connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
                select.setString(1, from);
                try (ResultSet found = select.executeQuery()) {
                    if (!found.next()) throw new SQLException("送金元がありません: " + from);
                    balance = found.getInt("balance");
                }
            }
            if (balance < amount) {
                throw new SQLException("残高が足りません: " + from);
            }

            try (PreparedStatement update =
                         connection.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
                update.setInt(1, balance - amount);
                update.setString(2, from);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE account SET balance = balance + ? WHERE id = ?")) {
                update.setInt(1, amount);
                update.setString(2, to);
                update.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO transfer(id, from_account, to_account, amount) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, transferId);
                insert.setString(2, from);
                insert.setString(3, to);
                insert.setInt(4, amount);
                insert.executeUpdate();
            }
        }
    }
}
