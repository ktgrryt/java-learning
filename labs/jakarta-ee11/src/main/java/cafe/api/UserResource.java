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

@Path("/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {
    @POST
    public Response create(@Valid CreateUser request) {
        UserView created = new UserView(request.name().strip(), request.email());
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    public record CreateUser(
            @NotBlank String name,
            @NotBlank @Email String email) {
    }

    public record UserView(String name, String email) {
    }
}
