package example;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
@ApplicationPath("/api") class Api extends Application {}
@Path("/orders") @Produces(MediaType.APPLICATION_JSON)
public class OrdersResource { @GET public String orders() { return "{\"status\":\"OK\"}"; } }
