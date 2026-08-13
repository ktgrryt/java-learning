package example.greeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 学習者が変更できない受け入れテスト。問題文の条件を実際のSpring MVCで固定する。 */
@WebMvcTest(GreetingController.class)
@Import(GreetingService.class)
class RequirementsTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsJsonThroughControllerAndInjectedService() throws Exception {
        mvc.perform(get("/api/greeting").param("name", " Java "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello, Java"));
    }

    @Test
    void rejectsBlankNameAtTheHttpBoundary() throws Exception {
        mvc.perform(get("/api/greeting").param("name", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNamesLongerThanTwentyCharacters() throws Exception {
        mvc.perform(get("/api/greeting").param("name", "a".repeat(21)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usesTheRequiredStartersAndHealthConfiguration() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("spring-boot-starter-validation"));
        assertTrue(pom.contains("spring-boot-starter-actuator"));
        assertTrue(pom.contains("spring-boot-starter-webmvc-test"));
        assertTrue(Class.forName("org.springframework.boot.health.contributor.Health") != null);

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(
                Path.of("src/main/resources/application.properties"))) {
            properties.load(input);
        }
        assertEquals("Hello", properties.getProperty("app.greeting.prefix"));
        assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("true", properties.getProperty("management.endpoint.health.probes.enabled"));
    }

    @Test
    void usesConstructorInjectionAndAConfiguredServiceBean() {
        var controllerConstructors = GreetingController.class.getDeclaredConstructors();
        assertEquals(1, controllerConstructors.length);
        assertArrayEquals(new Class<?>[]{GreetingService.class},
                controllerConstructors[0].getParameterTypes());

        assertTrue(GreetingService.class.isAnnotationPresent(Service.class));
        var serviceConstructors = GreetingService.class.getDeclaredConstructors();
        assertEquals(1, serviceConstructors.length);
        assertTrue(serviceConstructors[0].getParameters()[0].isAnnotationPresent(Value.class));
    }

    @Test
    void keepsTheLearnerTestAsAWebMvcSlice() throws Exception {
        String test = Files.readString(Path.of(
                "src/test/java/example/greeting/GreetingControllerTest.java"));
        assertTrue(test.contains("@WebMvcTest"));
        assertTrue(test.contains("MockMvc"));
        assertFalse(test.contains("@SpringBootTest"));
    }
}
