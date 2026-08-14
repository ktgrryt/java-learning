package cafe.api;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 注文を受け付けるResource（模範解答）。
 *
 * <p>要点は1つだけ。<b>{@code @Valid}を付けないと、宣言した制約は1つも動かない。</b>
 * 注釈を書いただけで守られていると思い込むのがいちばん危ない状態で、
 * これは実際に動かして不正な入力を送るまで気づけない。
 */
@Path("/orders")
public class OrderResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@Valid OrderRequest request) {
        String id = OrderStore.nextId();
        return Response.status(Response.Status.CREATED)
                .entity(new OrderAccepted(id, request.item(), request.quantity()))
                .build();
    }

    /** 応答の形。参照専用。 */
    public record OrderAccepted(String id, String item, int quantity) {
    }
}
