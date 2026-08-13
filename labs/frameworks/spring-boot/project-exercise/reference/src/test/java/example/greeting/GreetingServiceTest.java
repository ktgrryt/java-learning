package example.greeting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingServiceTest {
    @Test
    void formatsConfiguredGreetingWithoutStartingSpring() {
        GreetingService service = new GreetingService("Welcome");

        assertEquals("Welcome, Java", service.message(" Java "));
    }
}
