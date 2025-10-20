package com.david.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTest {
    @Test
    void userGettersSetters() {
        User u = new User();
        u.setId(1L);
        u.setEmail("a@b.com");
        u.setName("Alice");
        assertEquals(1L, u.getId());
        assertEquals("a@b.com", u.getEmail());
        assertEquals("Alice", u.getName());
    }
}
