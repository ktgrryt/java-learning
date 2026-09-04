// jshellで、配布前のクラスの振る舞いを確かめます。
//
// このスクリプトは次の形で動かされます。クラスパスは採点側が渡すので /env は不要です。
//
//   jshell -q --execution local -J-cp -J<出力先> --class-path <出力先> probe.jsh
//
// 値は環境変数で渡されます。**採点は違う値で2回動かします**。
// 定数を直接書くと片方で外れるので、必ず Pricing を呼んで計算してください。
//
//   JQ_PRICE  … 商品の単価
//   JQ_COUNT  … 個数
//   JQ_ODD    … 端数が出る単価
//
// 次の3行を、この順で出してください（税率はどれも10%です）。
//
//   tax=<JQ_PRICEの税込み価格>
//   total=<JQ_PRICEをJQ_COUNT個買ったときの合計>
//   round=<JQ_ODDの税込み価格>
//
// 最後に /exit を書きます（無いと終わりません）。

import cafe.pricing.Pricing;

int price = Integer.parseInt(System.getenv("JQ_PRICE"));
int count = Integer.parseInt(System.getenv("JQ_COUNT"));
int odd = Integer.parseInt(System.getenv("JQ_ODD"));

// TODO: tax= を出す

// TODO: total= を出す

// TODO: round= を出す

/exit
