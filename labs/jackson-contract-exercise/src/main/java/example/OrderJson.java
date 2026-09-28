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
    // TODO: 必須・null・末尾トークン・型の変換の方針を設定する
    private final JsonMapper mapper = JsonMapper.builder()
        // 準備: trueや123を名前の文字列へ暗黙変換しない。
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)).build();

    public OrderRequest read(String json) throws JsonProcessingException {
        // TODO: DTOへ変換した後、空白の名前と0以下の数量を拒否する
        return null;
    }

    public String write(OrderRequest request) throws JsonProcessingException {
        // TODO: ライブラリにエスケープと直列化を任せる
        return "";
    }
}
