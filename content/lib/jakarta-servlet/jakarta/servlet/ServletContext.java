package jakarta.servlet;

/**
 * アプリ全体で1つだけ存在する入れ物（アプリケーションスコープ）。
 *
 * リクエストスコープ・セッションスコープと違い、<b>利用者を問わず全員で共有される</b>。
 * マスタデータのキャッシュや全体の設定値を置く。
 *
 * <p>全員で共有される＝複数のスレッドから同時に触られるので、
 * 書き換えるなら排他制御が必要になる。
 */
public interface ServletContext {

    void setAttribute(String name, Object value);

    /** 無ければ null。 */
    Object getAttribute(String name);

    void removeAttribute(String name);

    /** web.xml や注釈で決めた初期化パラメータ。無ければ null。 */
    String getInitParameter(String name);

    /** ログ出力。本物ではアプリサーバのログに出る。 */
    void log(String message);
}
