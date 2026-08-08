package jakarta.servlet.http;

/**
 * 同じ利用者からの複数のリクエストにまたがって値を覚えておく入れ物（セッションスコープ）。
 *
 * HTTPは1回ごとに切れる（ステートレス）ので、ログイン状態やカートの中身のように
 * 「さっきの続き」を覚えるにはこれを使う。
 */
public interface HttpSession {

    /** セッションID。ブラウザにはCookie（JSESSIONID）で渡される。 */
    String getId();

    /** このリクエストで新しく作られたセッションなら true。 */
    boolean isNew();

    void setAttribute(String name, Object value);

    /** 無ければ null。 */
    Object getAttribute(String name);

    void removeAttribute(String name);

    /**
     * セッションを破棄する。ログアウトで使う。
     * 以降このオブジェクトを触ると {@link IllegalStateException} になる（本物と同じ）。
     */
    void invalidate();
}
