package jq.content;

/**
 * 学習者のコードと一緒にコンパイルする、同梱ライブラリのソース1つ。
 *
 * 中身は {@code content/lib/<ライブラリ名>/} 以下に置いた本物の .java ファイル。
 * {@link #path()} はそのディレクトリからの相対パスで、コンパイル用の作業ディレクトリへ
 * そのまま書き出す先になる（たとえば {@code jakarta/servlet/http/HttpServlet.java}）。
 * こうしておけば、ソースから package 宣言を読み解く必要がない。
 *
 * @param path    ライブラリのディレクトリからの相対パス。区切りは常に "/"
 * @param content ソースコード本文
 */
public record SourceFile(String path, String content) {
}
