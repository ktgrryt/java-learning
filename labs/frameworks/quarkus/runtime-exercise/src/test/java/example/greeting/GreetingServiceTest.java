package example.greeting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingServiceTest {
    @Test void formatsConfiguredMessageWithoutStartingQuarkus() {
        assertEquals("Welcome, Java", new GreetingService("Welcome").message(" Java "));
    }
}
