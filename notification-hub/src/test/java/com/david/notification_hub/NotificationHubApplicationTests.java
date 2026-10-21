package com.david.notification_hub;

import com.david.notification_hub.source.SourceRegistry;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.source.cheapshark.CheapSharkSource;
import com.david.notification_hub.source.ticketmaster.TicketmasterSource;
import com.david.notification_hub.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Boots the full context against a real PostgreSQL container. Because
 * ddl-auto is 'validate', this passing means Flyway built a schema that
 * actually matches the JPA entities - something the old H2 setup
 * (flyway disabled, ddl-auto create-drop) could never tell us.
 */
@SpringBootTest
class NotificationHubApplicationTests extends AbstractPostgresTest {

    @Autowired
    SourceRegistry sources;

    @Test
    void contextLoads() { }

    /** The real beans, wired by Spring - not the fakes SourceRegistryTest uses. */
    @Test
    void everySourceTypeHasItsSource() {
        assertInstanceOf(TicketmasterSource.class, sources.get(SourceType.TICKETMASTER));
        assertInstanceOf(CheapSharkSource.class, sources.get(SourceType.CHEAPSHARK));
    }
}
