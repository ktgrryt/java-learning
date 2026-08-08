import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBCの基本形を外部DBなしで練習するための小さなインメモリDB。
 * 公開する型はJDK標準のConnection / PreparedStatement / ResultSetそのもの。
 */
public final class MiniJdbc {
    private MiniJdbc() { }

    public static Connection open() {
        State state = new State();
        state.rows.put(1, new UserRow(1, "山田", 1000));
        state.rows.put(2, new UserRow(2, "佐藤", 500));
        state.rows.put(3, new UserRow(3, "鈴木", 0));
        return proxy(Connection.class, new ConnectionHandler(state));
    }

    private static final class UserRow {
        int id;
        String name;
        int balance;
        UserRow(int id, String name, int balance) { this.id = id; this.name = name; this.balance = balance; }
        UserRow copy() { return new UserRow(id, name, balance); }
    }

    private static final class State {
        LinkedHashMap<Integer, UserRow> rows = new LinkedHashMap<>();
        LinkedHashMap<Integer, UserRow> snapshot;
        boolean autoCommit = true;
        boolean closed;

        LinkedHashMap<Integer, UserRow> copyRows() {
            LinkedHashMap<Integer, UserRow> copy = new LinkedHashMap<>();
            rows.forEach((id, row) -> copy.put(id, row.copy()));
            return copy;
        }
        void beforeWrite() {
            if (!autoCommit && snapshot == null) snapshot = copyRows();
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final State state;
        ConnectionHandler(State state) { this.state = state; }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "prepareStatement" -> {
                    checkOpen();
                    yield MiniJdbc.proxy(PreparedStatement.class,
                            new StatementHandler(state, (String) args[0]));
                }
                case "setAutoCommit" -> { state.autoCommit = (boolean) args[0]; yield null; }
                case "getAutoCommit" -> state.autoCommit;
                case "commit" -> { checkOpen(); state.snapshot = null; yield null; }
                case "rollback" -> {
                    checkOpen();
                    if (state.snapshot != null) state.rows = state.snapshot;
                    state.snapshot = null;
                    yield null;
                }
                case "close" -> { state.closed = true; yield null; }
                case "isClosed" -> state.closed;
                case "unwrap" -> proxy;
                case "isWrapperFor" -> false;
                case "toString" -> "MiniJdbcConnection";
                default -> defaultValue(method.getReturnType());
            };
        }
        private void checkOpen() throws SQLException { if (state.closed) throw new SQLException("connection is closed"); }
    }

    private static final class StatementHandler implements InvocationHandler {
        private final State state;
        private final String sql;
        private final Map<Integer, Object> params = new LinkedHashMap<>();
        private boolean closed;

        StatementHandler(State state, String sql) { this.state = state; this.sql = normalize(sql); }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("setInt") || name.equals("setString") || name.equals("setLong")
                    || name.equals("setObject")) {
                params.put((Integer) args[0], args[1]);
                return null;
            }
            return switch (name) {
                case "executeQuery" -> resultSet(query());
                case "executeUpdate" -> update();
                case "close" -> { closed = true; yield null; }
                case "isClosed" -> closed;
                case "toString" -> "MiniPreparedStatement[" + sql + "]";
                default -> defaultValue(method.getReturnType());
            };
        }

        private List<UserRow> query() throws SQLException {
            if (sql.equals("select id, name, balance from users where id = ?")) {
                UserRow row = state.rows.get(intParam(1));
                return row == null ? List.of() : List.of(row.copy());
            }
            if (sql.equals("select id, name, balance from users where balance >= ? order by id")) {
                int min = intParam(1);
                List<UserRow> out = new ArrayList<>();
                for (UserRow row : state.rows.values()) if (row.balance >= min) out.add(row.copy());
                return out;
            }
            if (sql.equals("select id, name, balance from users order by id")) {
                return state.rows.values().stream().map(UserRow::copy).toList();
            }
            throw new SQLException("この教材で未対応のSELECTです: " + sql);
        }

        private int update() throws SQLException {
            if (sql.equals("update users set balance = ? where id = ?")) {
                state.beforeWrite();
                UserRow row = state.rows.get(intParam(2));
                if (row == null) return 0;
                row.balance = intParam(1);
                return 1;
            }
            if (sql.equals("update users set balance = balance + ? where id = ?")) {
                state.beforeWrite();
                UserRow row = state.rows.get(intParam(2));
                if (row == null) return 0;
                row.balance += intParam(1);
                return 1;
            }
            if (sql.equals("delete from users where id = ?")) {
                state.beforeWrite();
                return state.rows.remove(intParam(1)) == null ? 0 : 1;
            }
            throw new SQLException("この教材で未対応の更新SQLです: " + sql);
        }

        private int intParam(int index) throws SQLException {
            Object value = params.get(index);
            if (!(value instanceof Number n)) throw new SQLException("パラメータ" + index + "が未設定です");
            return n.intValue();
        }
    }

    private static ResultSet resultSet(List<UserRow> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            int index = -1;
            boolean closed;
            public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
                return switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getInt" -> intColumn(rows.get(index), args[0]);
                    case "getLong" -> (long) intColumn(rows.get(index), args[0]);
                    case "getString" -> stringColumn(rows.get(index), args[0]);
                    case "close" -> { closed = true; yield null; }
                    case "isClosed" -> closed;
                    case "wasNull" -> false;
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
    }

    private static int intColumn(UserRow row, Object key) throws SQLException {
        String column = key instanceof Integer i ? (i == 1 ? "id" : i == 3 ? "balance" : "") : key.toString().toLowerCase(Locale.ROOT);
        return switch (column) { case "id" -> row.id; case "balance" -> row.balance; default -> throw new SQLException("数値列ではありません: " + key); };
    }

    private static String stringColumn(UserRow row, Object key) throws SQLException {
        String column = key instanceof Integer i ? (i == 2 ? "name" : "") : key.toString().toLowerCase(Locale.ROOT);
        if (column.equals("name")) return row.name;
        throw new SQLException("文字列ではありません: " + key);
    }

    private static String normalize(String sql) {
        return sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
