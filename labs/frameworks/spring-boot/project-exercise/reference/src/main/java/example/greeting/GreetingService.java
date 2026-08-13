package example.greeting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class GreetingService {
    private final String prefix;

    GreetingService(@Value("${app.greeting.prefix:Hello}") String prefix) {
        this.prefix = prefix;
    }

    String message(String name) {
        return prefix + ", " + name.trim();
    }
}
