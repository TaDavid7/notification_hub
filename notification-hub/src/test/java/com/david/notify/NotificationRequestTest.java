package com.david.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationRequestTest {
    @Test
    void requestGettersSetters() {
        NotificationRequest r = new NotificationRequest();
        r.setId(10L);
        r.setTitle("Hello");
        r.setBody("World");
        r.setChannel("DISCORD"); // or your enum/field
        assertEquals(10L, r.getId());
        assertEquals("Hello", r.getTitle());
        assertEquals("World", r.getBody());
        assertEquals("DISCORD", r.getChannel());
    }
}
