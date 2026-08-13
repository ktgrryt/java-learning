package example.greeting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GreetingService {
    @Inject
    @ConfigProperty(name = "app.greeting.prefix", defaultValue = "Hello")
    String prefix;

    public GreetingService() {
        // CDI proxy用。
    }

    GreetingService(String prefix) {
        this.prefix = prefix;
    }

    public String message(String name) {
        return prefix + ", " + name.trim();
    }
}
