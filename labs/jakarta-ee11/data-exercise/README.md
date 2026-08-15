# Jakarta Data の lab

Jakarta Data 1.0 のリポジトリを宣言します。
Web・Jakarta EE編『Jakarta EE 11アップデート』の「章末演習：Jakarta EE 11アップデート」が
採点に使います。

```
sh run-runtime-lab.sh
```

必要なのは JDK と Maven です。依存は `jakarta.data-api` と `jakarta.persistence-api` の2つだけで、
どちらも小さいのですぐ取得できます。

## なぜ「実装なし」で採点できるのか

Jakarta Data では **実装を書きません**。インタフェースの宣言から、ビルド時に実装が生成されます。
つまり宣言の形そのものが仕様であり、間違っていれば動きません。

この lab は実装（Hibernateなど）を入れていません。確かめるのは次の2つです。

1. **APIに対してcompileが通ること。** `javac` が annotation の位置、戻り値の型、型引数を検査します。
   `@Find` を付けられない場所に付けた、`CrudRepository` の型引数を間違えた、といった誤りはここで落ちます。
2. **宣言の形が仕様どおりであること。** `@Repository`・`CrudRepository`・`@Find`・`@By`・`@OrderBy`・`@Query`
   の使い方を、コメントを除いたソースに対して確かめます。これらは Jakarta Data の仕様そのものなので、
   「一般的なJavaの書き方の指定」ではありません。

**測っていないこと:** 実際にDBへ問い合わせて結果が返るかは確かめていません。それには実装とDBが必要で、
この章の `48-5#3`（実サーバー配備の lab）とは別の環境が要ります。宣言が正しいことと、
問い合わせが意図どおりに動くことは別です。

## ディレクトリ

```
pom.xml                                   Jakarta Data 1.0 のAPIだけを依存に持つ
src/main/java/cafe/orders/Order.java      Entity（参照専用）
exercise/OrderRepository.java             宣言するリポジトリ（編集する）
reference/OrderRepository.java            模範解答
```
