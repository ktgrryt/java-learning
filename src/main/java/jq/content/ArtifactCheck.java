package jq.content;

/**
 * artifact 問題で、提出されたファイルに対して行う検査。
 *
 * @param type       検査方法（xpath / regex / property / jsonPointer / jsonPointerExists /
 *                   jsonPointerContains / githubActions）
 * @param expression XPath、正規表現、プロパティキー、JSON Pointer、CI要件IDのいずれか
 * @param expected   property / jsonPointer で期待する値、jsonPointerContains で配列に含まれるべき値
 *                   （それ以外では null）。JSONの真偽値や数値はそのままの型で比べる
 * @param message    学習者に示す検査内容。失敗時にもそのまま改善案として表示する
 */
public record ArtifactCheck(
        String type,
        String expression,
        Object expected,
        String message) {
}
