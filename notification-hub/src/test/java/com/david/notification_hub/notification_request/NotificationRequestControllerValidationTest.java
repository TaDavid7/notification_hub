package com.david.notification_hub.notification_request;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.http.MediaType;


import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;


//just for notes
//webmvcttest, starts controller
//@MockBean, mocks required beans
//@Autowired MockMvc, simulate http requests


@WebMvcTest(NotificationRequestController.class)
class NotificationRequestControllerValidationTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private NotificationIntakeService intake;

    @MockBean
    NotificationRequestRepository notificationRequestRepository;

    private static final String VALID_JSON = """
    {
      "title": "Hello",
      "body": "World",
      "channel": "DISCORD",
      "priority": "HIGH",
      "externalSource": "canvas:announcement",
      "externalId": "12345"
    }
    """;

    @Test
    void returns400ForEmptyBody() throws Exception {
        mvc.perform(post("/api/notifications")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 202, not 201: the row is enqueued and the dispatcher sends it later, so the
     * response can't claim the message was delivered.
     */
    @Test
    void post_validBody_returns202Accepted() throws Exception {
        NotificationRequest saved = new NotificationRequest();
        saved.setTitle("Hello");
        saved.setStatus(NotificationStatus.QUEUED);
        when(intake.enqueue(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NotificationIntakeService.Result(saved, false));

        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(NotificationStatus.QUEUED));
    }

    /** A re-post of the same source item is idempotent: 200 with the row that won. */
    @Test
    void post_duplicate_returns200() throws Exception {
        NotificationRequest existing = new NotificationRequest();
        existing.setTitle("Hello");
        existing.setStatus(NotificationStatus.SENT);
        when(intake.enqueue(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NotificationIntakeService.Result(existing, true));

        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(NotificationStatus.SENT));
    }
}
