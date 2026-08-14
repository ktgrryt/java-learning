/*
 * 挨拶を使うmodule。
 *
 * requires で使うmoduleを宣言する。宣言しないと、公開されているpackageでも読めない。
 */
module cafe.app {
    requires cafe.greeting;
}
