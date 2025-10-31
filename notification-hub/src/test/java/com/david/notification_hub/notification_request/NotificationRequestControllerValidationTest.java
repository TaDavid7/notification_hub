package com.david.notification_hub.notification_request;

import com.david.notification_hub.notification_request.NotificationRequestController;
import com.david.notification_hub.notification_request.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.http.MediaType;


import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


//just for notes
//webmvcttest, starts controller
//@MockBean, mocks required beans
//@Autowired MockMvc, simulate http requests


@WebMvcTest(NotificationRequestController.class)
class NotificationRequestControllerValidationTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    NotificationRequestRepository notificationRequestRepository;

    @Test
    void returns400ForEmptyBody() throws Exception {
        mvc.perform(post("/api/notifications")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void post_validBody_returns201() throws Exception{
        String validJson = """
      {
        "title": "Hello",
        "body": "World",
        "channel": "DISCORD",
        "priority": "HIGH"
      }
    """;
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isCreated());
    }
}
