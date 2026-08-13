package example.greeting;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/api/greeting")
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {
    @Inject GreetingService service;

    @GET
    public Greeting greeting(@QueryParam("name") String name) {
        // TODO: Jakarta Validationで空白と21文字以上をHTTP 400にする
        return new Greeting(service.message(name));
    }

    public record Greeting(String message) {}
}
