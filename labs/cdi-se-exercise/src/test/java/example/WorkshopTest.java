package example;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static example.Workshop.*;

class WorkshopTest {
    private WeldContainer start() {
        return new Weld().disableDiscovery().addBeanClasses(
            SmsSender.class, Producers.class, NotificationService.class, ReceiptObserver.class).initialize();
    }
    @Test void choosesMailProducerWithAnotherSenderPresent() {
        try (var c = start()) {
            assertEquals("sms:hello", c.select(SmsSender.class).get().send("hello"));
            assertEquals("mail:hello", c.select(NotificationService.class).get().notify("hello"));
        }
    }
    @Test void deliversBothEventsThroughContainer() {
        try (var c = start()) {
            var service = c.select(NotificationService.class).get();
            service.notify("first"); service.notify("second");
            assertEquals(java.util.List.of("mail:first", "mail:second"),
                c.select(ReceiptObserver.class).get().receipts());
        }
    }
    @Test void observerStateIsIsolatedBetweenContainers() {
        try (var c = start()) {
            assertTrue(c.select(ReceiptObserver.class).get().receipts().isEmpty());
        }
    }
}
