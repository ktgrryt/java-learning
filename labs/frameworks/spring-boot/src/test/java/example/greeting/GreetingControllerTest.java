package example.greeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GreetingController.class)
class GreetingControllerTest {
    @Autowired MockMvc mvc;

    @Test void greets() throws Exception {
        mvc.perform(get("/api/greeting").param("name", "Java"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Hello, Java"));
    }

    @Test void rejectsBlankName() throws Exception {
        mvc.perform(get("/api/greeting").param("name", ""))
                .andExpect(status().isBadRequest());
    }
}
