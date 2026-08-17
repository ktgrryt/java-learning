package jq.web;

/**
 * クライアント側の入力が不正なときに投げる。{@link ApiHandler} が 400 に変えて返す。
 *
 * <p>{@code /api} のハンドラは {@link ApiHandler} と {@link CafeApi} に分かれているので、
 * 両方から投げられるようここへ独立させてある（メッセージはそのまま画面に出るため、
 * 何をどう直せばよいかが分かる日本語で書く）。</p>
 */
final class BadRequest extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BadRequest(String message) {
        super(message);
    }
}
