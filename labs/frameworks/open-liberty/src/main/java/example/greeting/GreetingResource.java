package example.greeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/api/greeting")
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {
    @GET
    public Greeting greeting(@QueryParam("name") @NotBlank @Size(max = 20) String name) {
        return new Greeting("Hello, " + name);
    }

    public record Greeting(String message) {}
}

