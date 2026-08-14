/*
 * 縮小ランタイムに入れるmoduleの宣言。
 *
 * jlinkは、ここに書かれた依存を辿って必要なJDK moduleだけを集める。
 * java.net.httpを宣言すると、出来るimageはjava.base + java.net.http + このmoduleになる。
 */
module example.tools {
    requires java.net.http;
    exports example.tools;
}
