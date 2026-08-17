package jq.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部ライブラリを使わない最小限のJSONパーサ/ライタ。
 *
 * パース結果は Map<String,Object> / List<Object> / String / Double / Boolean / null に写す。
 * このアプリが必要とする範囲（コンテンツファイルとAPIのやり取り）だけを扱う。
 */
public final class MiniJson {

    /**
     * 入れ子の深さの上限。
     *
     * 配列とオブジェクトは相互再帰で読むので、深く入れ子になった入力は
     * そのままスタックを食い尽くす（{@code [[[[…]]]]} を数万段送るだけで
     * StackOverflowError になる）。{@code Error} は {@code RuntimeException} を
     * 捕まえている側では受け止められず、リクエストは応答なしで切れ、
     * 進捗ファイルの読み込みでは「壊れていたら退避して作り直す」復旧も働かない。
     * 深さで先に断ればどちらも普通のエラーとして扱える。
     *
     * このアプリが実際に扱うJSON（コンテンツファイル・APIのやり取り）は
     * 10段も使わないので、64もあれば正当な入力を弾く心配はない。
     */
    private static final int MAX_DEPTH = 64;

    private MiniJson() {
    }

    // ---------------------------------------------------------------- parse

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new JsonException("末尾に余分な文字があります (位置 " + p.pos + ")");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("JSONオブジェクトを期待しましたが " + typeName(value) + " でした");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String src;
        private int pos;
        /** いま何段の入れ子の中にいるか。{@link #MAX_DEPTH} を超えたら読むのをやめる。 */
        private int depth;

        Parser(String src) {
            this.src = src;
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (pos >= src.length()) {
                throw new JsonException("値が来るはずの位置で入力が終わりました");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            enter();
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (peek() == '}') {
                pos++;
                depth--;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("キーの \" がありません (位置 " + pos + ")");
                }
                String key = readString();
                skipWs();
                if (peek() != ':') {
                    throw new JsonException("キー \"" + key + "\" の後に : がありません");
                }
                pos++;
                skipWs();
                map.put(key, readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    depth--;
                    return map;
                } else {
                    throw new JsonException("オブジェクト内で , か } を期待しました (位置 " + pos + ")");
                }
            }
        }

        List<Object> readArray() {
            enter();
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (peek() == ']') {
                pos++;
                depth--;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    depth--;
                    return list;
                } else {
                    throw new JsonException("配列内で , か ] を期待しました (位置 " + pos + ")");
                }
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new JsonException("文字列が閉じられていません");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                // エスケープの途中で入力が終わっていたら、ここで自分の例外にして断る。
                // charAt / substring / parseInt に任せると
                // 「Range [11, 15) out of bounds for length 13」のようなJDKの生の
                // メッセージがそのまま利用者と教材の書き手へ出てしまう（他の失敗は
                // すべて日本語と位置で返しているので、ここだけ読めなくなる）。
                if (pos >= src.length()) {
                    throw new JsonException("\\ の後で入力が終わりました (位置 " + pos + ")");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("\\u の後の4桁が足りません (位置 " + pos + ")");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException(
                                    "\\u の後が16進4桁ではありません: \"" + hex + "\" (位置 " + pos + ")");
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("未知のエスケープ \\" + esc);
                }
            }
        }

        Double readNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = src.substring(start, pos);
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException e) {
                throw new JsonException("数値として読めません: \"" + token + "\"");
            }
        }

        /**
         * 入れ子へ1段入る。深すぎる入力はここで断る。
         *
         * 抜けるときの {@code depth--} は、配列・オブジェクトを読み終えて return する
         * 直前に置いてある。例外で抜けるときは復元しないが、そのパーサはもう使わないので
         * 問題にならない（try/finally を挟むより読みやすさを取った）。
         */
        void enter() {
            if (++depth > MAX_DEPTH) {
                throw new JsonException("入れ子が深すぎます（上限 " + MAX_DEPTH + " 段, 位置 " + pos + "）");
            }
        }

        void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonException(literal + " を期待しました (位置 " + pos + ")");
            }
            pos += literal.length();
        }

        char peek() {
            if (pos >= src.length()) {
                throw new JsonException("入力が予期せず終わりました");
            }
            return src.charAt(pos);
        }
    }

    // ---------------------------------------------------------------- write

    /** Map / List / String / Number / Boolean / null をJSON文字列にする。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            // JSONに NaN と Infinity は無い。そのまま書くと "NaN" / "Infinity" という
            // 裸の語になり、ブラウザ側の JSON.parse が応答全体で失敗する（画面が丸ごと
            // 止まり、しかも原因がここだと分かりにくい）。値を1つ捨てる方がましなので
            // null にする。数として扱えない値を作った側の不具合なので、痕跡は残す。
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                System.err.println("JSONにできない数値なので null にしました: " + d);
                sb.append("null");
            } else if (d == Math.rint(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, o);
            }
            sb.append(']');
        } else {
            throw new JsonException("JSONに変換できない型です: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // 制御文字と U+2028 / U+2029 (JS側で構文エラーになりうる) はエスケープする
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------- helpers

    /** マップから文字列を取り出す。無ければ fallback。 */
    public static String str(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v instanceof String s ? s : fallback;
    }

    /** マップから文字列を取り出す。無ければ例外。 */
    public static String requireStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new JsonException("\"" + key + "\" が無い、または文字列ではありません");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object v) {
        if (!(v instanceof Map)) {
            throw new JsonException("オブジェクトを期待しましたが " + typeName(v) + " でした");
        }
        return (Map<String, Object>) v;
    }

    public static int intOf(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "オブジェクト";
        }
        if (v instanceof List) {
            return "配列";
        }
        return v.getClass().getSimpleName();
    }

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }
}
