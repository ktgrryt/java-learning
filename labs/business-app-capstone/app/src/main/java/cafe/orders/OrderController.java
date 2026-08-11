package cafe.orders;

public final class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public ApiResponse cancel(long orderId, CancelOrderRequest request) {
        // TODO: Serviceの結果と例外をrequirements.mdのAPI契約へ変換する
        return new ApiResponse(500, "TODO");
    }
}
