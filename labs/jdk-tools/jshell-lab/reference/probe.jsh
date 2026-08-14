import cafe.pricing.Pricing;

int price = Integer.parseInt(System.getenv("JQ_PRICE"));
int count = Integer.parseInt(System.getenv("JQ_COUNT"));
int odd = Integer.parseInt(System.getenv("JQ_ODD"));

// 税込み価格。端数は切り捨て（Pricingの実装がそうなっている）
System.out.println("tax=" + Pricing.withTax(price, 10));

// 個数ぶんの合計
System.out.println("total=" + Pricing.total(price, count, 10));

// 端数が出る単価。10%が小数になるので切り捨てられる
System.out.println("round=" + Pricing.withTax(odd, 10));

/exit
