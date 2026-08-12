package example.greeting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GreetingService {
    private final String prefix;

    @Inject
    public GreetingService(@ConfigProperty(name = "app.greeting.prefix", defaultValue = "Hello") String prefix) {
        this.prefix = prefix;
    }

    public String message(String name) {
        return prefix + ", " + name.trim();
    }
}
