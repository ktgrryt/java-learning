/*
 * 縮小ランタイムに入れるmoduleの宣言。
 *
 * jlinkは、ここに書かれた依存を辿って必要なJDK moduleだけを集める。
 * Menu.javaが使っているものを宣言していないと、compileの時点で止まる。
 */
module example.tools {
    /* TODO Menu.javaが使うJDK moduleを宣言する */
    exports example.tools;
}
