package cafe.api;

import jakarta.annotation.security.RolesAllowed;
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
 * 注文のAPI（模範解答）。
 *
 * <p>要点は3つ。
 *
 * <ul>
 *   <li><b>誰が呼べるかを、呼べる場所ごとに宣言する。</b>{@code @RolesAllowed}が無い
 *       メソッドは公開です。「認証しているはず」という前提はコードのどこにも書かれていません。</li>
 *   <li><b>401と403は違う。</b>401は「あなたが誰か分からない」（資格情報を出し直せば通るかも）、
 *       403は「あなただと分かったが権限が無い」（出し直しても通らない）。
 *       この区別はコンテナが付けますが、宣言していなければどちらも起きません。</li>
 *   <li><b>内部の形をそのまま返さない。</b>外向きのrecordへ移し替えれば、
 *       内部にフィールドが増えても勝手に外へ出ていきません。
 *       「返す項目を選ぶ」ではなく「返す形を別に持つ」のが要点です。</li>
 * </ul>
 */
@Path("/")
public class OrderResource {

    @Inject
    private OrderRepository repository;

    /** 生存確認。役割を要求しないので公開のまま。 */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok("{\"status\":\"UP\"}").build();
    }

    /** 注文の一覧。staffの役割が必要。 */
    @GET
    @Path("/orders")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("staff")
    public Response list() {
        List<OrderView> view = repository.findAll().stream()
                .map(OrderResource::toView)
                .toList();
        return Response.ok(view).build();
    }

    /** 注文の削除。managerの役割だけが呼べる。 */
    @DELETE
    @Path("/orders/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("manager")
    public Response delete(@PathParam("id") String id) {
        if (!repository.delete(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    /** 外向きの形。内部にフィールドが増えても、ここに書いていないものは出ていかない。 */
    public record OrderView(String id, String item, int quantity) {
    }

    private static OrderView toView(Order order) {
        return new OrderView(order.id(), order.item(), order.quantity());
    }
}
