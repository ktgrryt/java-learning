package cafe.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 注文を受け付けるResource。
 *
 * <p>制約を宣言しても、<b>ここで有効にしないと何も起きません</b>。
 * いまは不正な入力もそのまま受け付け、しかも作成を200で返しています。
 */
@Path("/orders")
public class OrderResource {

    /**
     * 注文を受け付ける。
     *
     * <p>正しい入力なら<b>201</b>と{@code {"id":"ORD-...","item":...,"quantity":...}}を返す。
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(OrderRequest request) {
        // TODO: 受け取ったデータの制約を検査させる（1語の注釈を足すだけ）
        // TODO: 作ったときのステータスコードを見直す（200は「成功した」だけで、
        //       「新しく作った」を表さない。呼び出し側は作成されたかを区別できない）
        String id = OrderStore.nextId();
        return Response.ok(new OrderAccepted(id, request.item(), request.quantity())).build();
    }

    /** 応答の形。参照専用。 */
    public record OrderAccepted(String id, String item, int quantity) {
    }
}
