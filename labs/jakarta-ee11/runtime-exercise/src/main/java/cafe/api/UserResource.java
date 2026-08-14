package cafe.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 利用者の登録を受け付ける。
 *
 * 今は「受け付けた」ことしか返していない。次の3つを実サーバーの応答として成り立たせる。
 *
 * TODO 1: 新規作成なのでHTTP 201を返す
 * TODO 2: 名前が空、メールが形式違反の要求をHTTP 400で拒否する
 *         （recordの構成要素へBean Validationの制約を付け、引数を検証対象にする）
 * TODO 3: 応答へ登録時刻を入れる。要求のregisteredAtをInstantとして持つと、
 *         JSON-BがISO-8601の文字列として書き出す
 */
@Path("/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @POST
    public Response create(CreateUser request) {
        UserView created = new UserView(request.name(), request.email());
        return Response.ok(created).build();
    }

    public record CreateUser(String name, String email, String registeredAt) {
    }

    public record UserView(String name, String email) {
    }
}
