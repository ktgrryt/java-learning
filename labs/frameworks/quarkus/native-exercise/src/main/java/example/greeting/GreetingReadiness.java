package example.greeting;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class GreetingReadiness implements HealthCheck {
    @Override public HealthCheckResponse call() {
        // TODO: Native containerが受付可能ならUPを返す
        return HealthCheckResponse.down("native-greeting");
    }
}
