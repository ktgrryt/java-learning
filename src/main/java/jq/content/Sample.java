package jq.content;

/**
 * 解説中の「動かせるサンプルコード」。
 *
 * @param caption サンプルの見出し
 * @param code    そのまま実行できるJavaコード
 * @param stdin   サンプル実行時に標準入力へ流す文字列（不要なら空文字）
 */
public record Sample(String caption, String code, String stdin) {
}
