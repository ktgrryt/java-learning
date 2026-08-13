package example.greeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/greeting")
class GreetingController {
    // TODO: newで作らず、constructor injectionでServiceを受け取る
    private final GreetingService service = new GreetingService("Hello");

    @GetMapping
    Greeting greeting(@RequestParam String name) {
        // TODO: 空白と21文字以上をHTTP 400にする
        return new Greeting(service.message(name));
    }

    record Greeting(String message) {}
}
