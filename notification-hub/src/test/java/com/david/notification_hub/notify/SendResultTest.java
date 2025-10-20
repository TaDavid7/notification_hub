package com.david.notification_hub.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SendResultTest {
    @Test
    void createSendResultWithArguments() {
        SendResult result = new SendResult(true, "DISCORD", "ok");
        assertNotNull(result);
    }
}
