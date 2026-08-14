package cafe.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

/**
 * 利用者の登録を受け付ける。
 *
 * recordはgetterを書かなくてもJSON-Bが読み書きする。Instantは既定でISO-8601になる。
 * 検証はBean Validationがrecordの構成要素へ付いた制約で行い、境界はHTTPの400として現れる。
 */
@Path("/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @POST
    public Response create(@Valid CreateUser request) {
        UserView created = new UserView(
                request.name().strip(),
                request.email(),
                Instant.parse(request.registeredAt()));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    public record CreateUser(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String registeredAt) {
    }

    public record UserView(String name, String email, Instant registeredAt) {
    }
}
