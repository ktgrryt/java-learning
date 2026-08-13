package example.greeting;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {
    public String message(String name) {
        // TODO: app.greeting.prefixを注入して利用する
        return "TODO, " + name.trim();
    }
}
