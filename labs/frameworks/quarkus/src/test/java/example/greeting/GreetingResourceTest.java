package example.greeting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {
    @Test void greets() {
        given().queryParam("name", "Java").when().get("/api/greeting")
                .then().statusCode(200).body("message", is("Hello, Java"));
    }

    @Test void rejectsBlankName() {
        given().queryParam("name", "").when().get("/api/greeting").then().statusCode(400);
    }

    @Test void rejectsTooLongName() {
        given().queryParam("name", "a".repeat(21)).when().get("/api/greeting").then().statusCode(400);
    }
}
