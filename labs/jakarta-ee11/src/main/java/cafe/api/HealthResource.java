package cafe.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @GET
    public Health health() {
        return new Health("UP");
    }

    public record Health(String status) {
    }
}
