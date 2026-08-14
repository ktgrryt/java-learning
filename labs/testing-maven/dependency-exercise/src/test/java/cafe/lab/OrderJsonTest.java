package cafe.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** この演習では変更しない。依存の版がそろっていれば通る。 */
class OrderJsonTest {

    @Test
    void writesOrderAsJson() throws Exception {
        assertEquals("{\"id\":1,\"status\":\"PAID\",\"total\":1200}",
                OrderJson.write(1L, "PAID", 1200));
    }

    @Test
    void readsStatusBack() throws Exception {
        assertEquals("NEW", OrderJson.status("{\"id\":2,\"status\":\"NEW\",\"total\":0}"));
    }
}
