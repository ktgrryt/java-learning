package jakarta.servlet.http;

/**
 * ブラウザに預けておく小さな名前と値の組。
 *
 * セッションと違い、値そのものがブラウザ側に保存されて毎回送り返されてくる。
 * だから<b>秘密の情報を入れてはいけない</b>（利用者が中身を見て書き換えられる）。
 */
public class Cookie {

    private final String name;
    private String value;
    private int maxAge = -1;
    private String path;
    private boolean httpOnly;
    private boolean secure;

    public Cookie(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cookieの名前が空です");
        }
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /**
     * 保存される秒数。
     *
     * <ul>
     *   <li>{@code -1}（既定）… ブラウザを閉じるまで</li>
     *   <li>{@code 0} … すぐ削除（Cookieを消したいときはこれ）</li>
     *   <li>正の数 … その秒数だけ保存</li>
     * </ul>
     */
    public void setMaxAge(int seconds) {
        this.maxAge = seconds;
    }

    public int getMaxAge() {
        return maxAge;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    /** true にすると JavaScript から読めなくなる（XSSでの盗み出し対策）。 */
    public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    /** true にすると HTTPS のときだけ送られる。 */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean getSecure() {
        return secure;
    }
}
