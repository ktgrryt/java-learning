package cafe.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * 注文のAPI。
 *
 * <p>いまは<b>誰でも全部できます</b>。資格情報なしでも一覧が見えて、削除もできます。
 * さらに内部の形をそのまま返しているので、社内メモまで外へ出ています。
 *
 * <p>満たすこと:
 *
 * <ul>
 *   <li>{@code GET /api/health} … 資格情報なしで200（ここは公開のまま）</li>
 *   <li>{@code GET /api/orders} … {@code staff}の役割が必要。
 *       資格情報が無ければ401、役割が足りなければ403</li>
 *   <li>{@code DELETE /api/orders/{id}} … {@code manager}の役割が必要。
 *       {@code staff}だけの利用者は403</li>
 *   <li>返すJSONに{@code internalNote}を<b>含めない</b>。
 *       {@code customer}も外向きには返さない</li>
 * </ul>
 *
 * <p>利用者は`aki`（staff）・`mgr`（staff,manager）・`bob`（役割なし）です。
 * パスは採点の足場が使うので変えないこと。
 */
@Path("/")
public class OrderResource {

    @Inject
    private OrderRepository repository;

    /** 生存確認。ここは公開のままにする。 */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok("{\"status\":\"UP\"}").build();
    }

    /**
     * 注文の一覧。
     *
     * <p>TODO: staffの役割を要求する。TODO: 外向きの形へ変えて返す。
     */
    @GET
    @Path("/orders")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        List<Order> orders = repository.findAll();
        return Response.ok(orders).build();
    }

    /**
     * 注文の削除。
     *
     * <p>TODO: managerの役割だけが呼べるようにする。
     */
    @DELETE
    @Path("/orders/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        if (!repository.delete(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    // TODO: 外向きの形（社内メモと顧客名を含まないrecord）を作る
}
