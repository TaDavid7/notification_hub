package com.david.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SendResultTest {
    @Test
    void valuesAndValueOf() {
        for (SendResult v : SendResult.values()) {
            assertNotNull(v.name());
        }
        assertEquals(SendResult.SUCCESS, SendResult.valueOf("SUCCESS"));
    }
}
