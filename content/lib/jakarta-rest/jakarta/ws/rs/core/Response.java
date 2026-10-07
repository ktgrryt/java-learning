package jakarta.ws.rs.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** 教材でレスポンス設計を試すための最小版。 */
public final class Response {
    private final int status;
    private final Object entity;
    private final Map<String, Object> headers;

    private Response(int status, Object entity, Map<String, Object> headers) {
        this.status = status;
        this.entity = entity;
        this.headers = Map.copyOf(headers);
    }
    public int getStatus() { return status; }
    public Object getEntity() { return entity; }
    public String getHeaderString(String name) {
        Object value = headers.get(name);
        return value == null ? null : value.toString();
    }
    public static ResponseBuilder status(int status) { return new ResponseBuilder(status); }
    public static ResponseBuilder status(Status status) { return status(status.getStatusCode()); }
    public static ResponseBuilder ok(Object entity) { return status(200).entity(entity); }
    public static ResponseBuilder created(URI location) { return status(201).header("Location", location); }
    public static ResponseBuilder noContent() { return status(204); }

    /** 本物の Response.Status のうち、教材で使う状態だけ。 */
    public enum Status {
        OK(200), CREATED(201), ACCEPTED(202), NO_CONTENT(204),
        BAD_REQUEST(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), CONFLICT(409),
        INTERNAL_SERVER_ERROR(500), SERVICE_UNAVAILABLE(503);

        private final int code;
        Status(int code) { this.code = code; }
        public int getStatusCode() { return code; }
    }

    public static final class ResponseBuilder {
        private final int status;
        private Object entity;
        private final Map<String, Object> headers = new LinkedHashMap<>();
        private ResponseBuilder(int status) { this.status = status; }
        public ResponseBuilder entity(Object value) { this.entity = value; return this; }
        public ResponseBuilder header(String name, Object value) { headers.put(name, value); return this; }
        public Response build() { return new Response(status, entity, headers); }
    }
}
