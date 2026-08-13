package example.greeting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;

/** 学習者が編集できない受け入れテスト。 */
@QuarkusTest
class AcceptanceTest {
    @Test void configuredApiAndValidationWorkTogether() {
        given().queryParam("name", " Java ").when().get("/api/greeting")
                .then().statusCode(200).body("message", is("Welcome, Java"));
        given().queryParam("name", " ").when().get("/api/greeting").then().statusCode(400);
        given().queryParam("name", "a".repeat(21)).when().get("/api/greeting").then().statusCode(400);
    }

    @Test void readinessContainsGreetingCheck() {
        given().when().get("/q/health/ready").then().statusCode(200)
                .body("status", is("UP"))
                .body("checks.name", hasItem("greeting"));
    }
}
