package jakarta.ws.rs.ext;

import jakarta.ws.rs.core.Response;

public interface ExceptionMapper<E extends Throwable> { Response toResponse(E exception); }
