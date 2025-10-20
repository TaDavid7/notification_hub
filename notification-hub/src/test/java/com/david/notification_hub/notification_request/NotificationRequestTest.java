package com.david.notification_hub.notification_request;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationRequestTest {
    @Test
    void createNotificationRequest() {
        NotificationRequest r = new NotificationRequest();
        assertNotNull(r);
    }
}
