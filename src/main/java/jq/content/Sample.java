package jq.content;

/**
 * 解説中の「動かせるサンプルコード」。
 *
 * @param caption サンプルの見出し
 * @param code    そのまま実行できるJavaコード
 * @param stdin   サンプル実行時に標準入力へ流す文字列（不要なら空文字）
 * @param expected 期待する標準出力。出力が環境依存などで検査しない場合は {@code null}
 */
public record Sample(String caption, String code, String stdin, String expected) {
}
