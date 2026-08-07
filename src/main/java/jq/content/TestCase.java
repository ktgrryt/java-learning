package jq.content;

/**
 * 練習問題1件の判定に使う入出力の組。
 *
 * @param label    表示名（「n=4 のとき」など）
 * @param stdin    標準入力へ流す文字列。入力を使わない問題では空文字
 * @param expected 期待する標準出力
 * @param hidden   true なら提出前は内容を隠す（失敗したときだけ開示する）
 */
public record TestCase(String label, String stdin, String expected, boolean hidden) {
}
