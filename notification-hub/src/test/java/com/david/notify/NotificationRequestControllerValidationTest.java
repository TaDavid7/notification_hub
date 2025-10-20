package com.david.notify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationRequestController.class)
class NotificationRequestControllerValidationTest {

    @Autowired MockMvc mvc;

    // Mock any collaborators the controller autowires:
    @MockBean NotificationService notificationService;

    @Test
    void returns400ForEmptyBody() throws Exception {
        mvc.perform(post("/api/notifications")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
