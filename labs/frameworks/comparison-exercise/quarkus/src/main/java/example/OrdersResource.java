package example;
import jakarta.ws.rs.*;
@Path("/api/orders")
public class OrdersResource { @GET public String orders() { return "{\"status\":\"OK\"}"; } }
