package cafe.orders;

public final class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public ApiResponse cancel(long orderId, CancelOrderRequest request) {
        try {
            service.cancel(orderId, request);
            return new ApiResponse(204, "");
        } catch (InvalidRequestException e) {
            return new ApiResponse(400, "invalid_request");
        } catch (OrderNotFoundException e) {
            return new ApiResponse(404, "order_not_found");
        } catch (OrderForbiddenException e) {
            return new ApiResponse(403, "forbidden");
        } catch (OrderConflictException e) {
            return new ApiResponse(409, "order_not_cancellable");
        } catch (RuntimeException e) {
            return new ApiResponse(500, "internal_error");
        }
    }
}
