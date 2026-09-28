package example;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

class OrderJsonTest {
    private final OrderJson codec = new OrderJson();
    @Test void roundTripEscapesAndUnicode() throws Exception {
        var value = new OrderJson.OrderRequest("茶\"\\\n😀", 3);
        String json = codec.write(value);
        assertEquals(value, codec.read(json));
        var tree = JsonMapper.builder().build().readTree(json);
        assertEquals(value.name(), tree.get("name").textValue());
        assertEquals(3, tree.get("quantity").intValue());
    }
    @Test void readsReorderedFields() throws Exception {
        assertEquals(new OrderJson.OrderRequest("tea", 2),
            codec.read("{\"quantity\":2,\"name\":\"tea\"}"));
    }
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\":\"tea\",\"quantity\":1.5}",
        "{\"name\":true,\"quantity\":2}",
        "{", "null", "{}", "{\"name\":\"tea\"}", "{\"quantity\":2}",
        "{\"name\":null,\"quantity\":2}", "{\"name\":\"tea\",\"quantity\":null}",
        "{\"name\":\"  \",\"quantity\":2}", "{\"name\":\"tea\",\"quantity\":0}",
        "{\"name\":\"tea\",\"quantity\":-1}", "{\"name\":\"tea\",\"quantity\":\"2\"}",
        "{\"name\":\"tea\",\"quantity\":2} {}",
        "{\"name\":\"tea\",\"quantity\":2,\"admin\":true}"
    })
    void rejectsBrokenContract(String json) {
        assertThrows(Exception.class, () -> codec.read(json));
    }
}
