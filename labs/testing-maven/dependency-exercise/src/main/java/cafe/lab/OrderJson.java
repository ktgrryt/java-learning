package cafe.lab;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注文をJSONへ直す。この演習では変更しない。
 *
 * jackson-databindは、同じ版のjackson-coreがあることを前提に作られている。
 * 版がずれていると、コンパイルは通っても実行時に落ちる。
 */
public final class OrderJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderJson() {
    }

    public static String write(long id, String status, int total) throws Exception {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("status", status);
        order.put("total", total);
        return MAPPER.writeValueAsString(order);
    }

    public static String status(String json) throws Exception {
        return MAPPER.readTree(json).get("status").asText();
    }
}
