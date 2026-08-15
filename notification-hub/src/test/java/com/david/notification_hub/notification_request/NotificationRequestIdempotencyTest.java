package com.david.notification_hub.notification_request;

import com.david.notification_hub.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the partial unique index from V5__idempotency_unique_key.sql.
 *
 * This is the behaviour the controller's duplicate handling leans on, and it
 * can only be tested against real PostgreSQL - H2 has no partial indexes, and
 * the old test profile disabled Flyway entirely so the index never existed.
 */
@SpringBootTest
class NotificationRequestIdempotencyTest extends AbstractPostgresTest {

    @Autowired
    NotificationRequestRepository repo;

    private static NotificationRequest request(String externalId, String channel) {
        NotificationRequest r = new NotificationRequest();
        r.setTitle("title");
        r.setBody("body");
        r.setPriority("NORMAL");
        r.setChannel(channel);
        r.setStatus("QUEUED");
        r.setExternalSource("canvas:announcement");
        r.setExternalId(externalId);
        return r;
    }

    @Test
    void sameSourceIdAndChannelIsRejected() {
        repo.saveAndFlush(request("dup-1", "DISCORD"));

        // Second insert of the same (source, id, channel) must hit the unique index
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.saveAndFlush(request("dup-1", "DISCORD")));
    }

    @Test
    void sameSourceIdOnDifferentChannelIsAllowed() {
        // The index includes channel, so one Canvas item can fan out to both
        repo.saveAndFlush(request("fanout-1", "DISCORD"));
        repo.saveAndFlush(request("fanout-1", "SLACK"));

        assertTrue(repo.findByExternalSourceAndExternalIdAndChannel(
                "canvas:announcement", "fanout-1", "DISCORD").isPresent());
        assertTrue(repo.findByExternalSourceAndExternalIdAndChannel(
                "canvas:announcement", "fanout-1", "SLACK").isPresent());
    }

    @Test
    void lookupUsedByDuplicateHandlingFindsTheOriginalRow() {
        NotificationRequest saved = repo.saveAndFlush(request("lookup-1", "SLACK"));

        // Mirrors the recovery path in NotificationRequestController.create
        NotificationRequest found = repo.findByExternalSourceAndExternalIdAndChannel(
                "canvas:announcement", "lookup-1", "SLACK").orElseThrow();

        assertEquals(saved.getId(), found.getId());
        assertNotNull(found.getCreatedAt());
    }
}
