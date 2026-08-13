package example.greeting;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class GreetingReadiness implements HealthCheck {
    @Override public HealthCheckResponse call() {
        // TODO: リクエストを受けられる状態をUPとして報告する
        return HealthCheckResponse.down("greeting");
    }
}
