/*
 * 挨拶を提供するmodule。
 *
 * 公開するのは cafe.greeting だけ。cafe.greeting.internal は公開しないので、
 * 他のmoduleからは参照できない（コンパイル時に守られる）。
 */
module cafe.greeting {
    exports cafe.greeting;
}
