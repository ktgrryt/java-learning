package example.greeting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {
    @Test void usesConfiguredPrefix() {
        given().queryParam("name", "Java").when().get("/api/greeting")
                .then().statusCode(200).body("message", is("Welcome, Java"));
    }

    @Test void validatesHttpInput() {
        given().queryParam("name", "").when().get("/api/greeting").then().statusCode(400);
        given().queryParam("name", "a".repeat(21)).when().get("/api/greeting").then().statusCode(400);
    }

    @Test void exposesReadiness() {
        given().when().get("/q/health/ready")
                .then().statusCode(200).body("status", is("UP"));
    }
}
