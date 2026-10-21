package com.david.notification_hub;

import com.david.notification_hub.polling.PollingProperties;
import com.david.notification_hub.source.SourceRegistry;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.source.cheapshark.CheapSharkSource;
import com.david.notification_hub.source.ticketmaster.TicketmasterSource;
import com.david.notification_hub.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Autowired
    PollingProperties polling;

    @Test
    void contextLoads() { }

    /** The real beans, wired by Spring - not the fakes SourceRegistryTest uses. */
    @Test
    void everySourceTypeHasItsSource() {
        assertInstanceOf(TicketmasterSource.class, sources.get(SourceType.TICKETMASTER));
        assertInstanceOf(CheapSharkSource.class, sources.get(SourceType.CHEAPSHARK));
    }

    /**
     * The hosts in application.yml contain dots, which Spring reads as nesting unless the
     * key is bracketed - and an unbound limit fails silently, by never limiting.
     */
    @Test
    void theApiRateLimitsInApplicationYmlBind() {
        assertEquals(new PollingProperties.RateLimit(4, 4500), polling.rateLimits().get("app.ticketmaster.com"));
        assertEquals(new PollingProperties.RateLimit(1, 0), polling.rateLimits().get("www.cheapshark.com"));
    }
}
