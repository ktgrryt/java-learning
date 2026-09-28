package example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

public class OrderJson {
    public record OrderRequest(String name, Integer quantity) {}
    private final JsonMapper mapper = JsonMapper.builder()
        // 準備: trueや123を名前の文字列へ暗黙変換しない。
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .build();

    public OrderRequest read(String json) throws JsonProcessingException {
        OrderRequest request = mapper.readValue(json, OrderRequest.class);
        if (request == null || request.name() == null || request.name().isBlank()
                || request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException("nameと正のquantityが必要です");
        }
        return request;
    }

    public String write(OrderRequest request) throws JsonProcessingException {
        return mapper.writeValueAsString(request);
    }
}
