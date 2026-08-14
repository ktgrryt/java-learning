package cafe.ops;

/**
 * 在庫解放の結果。
 *
 * @param ok        解放できたか
 * @param errorCode 失敗したときの短い名前。監視で数えるので、下流の内部情報は入れない
 * @param attempts  下流を呼んだ回数
 */
public record ReleaseResult(boolean ok, String errorCode, int attempts) {

    public static ReleaseResult success(int attempts) {
        return new ReleaseResult(true, "", attempts);
    }

    public static ReleaseResult failure(String errorCode, int attempts) {
        return new ReleaseResult(false, errorCode, attempts);
    }
}
