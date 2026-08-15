package com.david.notification_hub;

import com.david.notification_hub.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full context against a real PostgreSQL container. Because
 * ddl-auto is 'validate', this passing means Flyway built a schema that
 * actually matches the JPA entities - something the old H2 setup
 * (flyway disabled, ddl-auto create-drop) could never tell us.
 */
@SpringBootTest
class NotificationHubApplicationTests extends AbstractPostgresTest {

    @Test
    void contextLoads() { }
}
