package example.greeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/greeting")
class GreetingController {
    @GetMapping
    Greeting greeting(@RequestParam @NotBlank @Size(max = 20) String name) {
        return new Greeting("Hello, " + name);
    }

    record Greeting(String message) {}
}
