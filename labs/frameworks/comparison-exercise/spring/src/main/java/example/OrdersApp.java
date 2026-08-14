package example;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
@SpringBootApplication @RestController
public class OrdersApp {
    @GetMapping("/api/orders") public String orders() { return "{\"status\":\"OK\"}"; }
    public static void main(String[] a) { SpringApplication.run(OrdersApp.class, a); }
}
