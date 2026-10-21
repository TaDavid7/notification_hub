package com.david.notification_hub.notification_request;

import com.david.notification_hub.support.AbstractPostgresTest;
import com.david.notification_hub.user.User;
import com.david.notification_hub.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the dedupe index: V5's partial unique index on (source, id, channel), widened
 * by V8 to include the user.
 *
 * This is the behaviour the intake service's duplicate handling leans on, and it
 * can only be tested against real PostgreSQL - H2 has no partial indexes or
 * NULLS NOT DISTINCT, and the old test profile disabled Flyway entirely so the
 * index never existed.
 */
@SpringBootTest
class NotificationRequestIdempotencyTest extends AbstractPostgresTest {

    @Autowired
    NotificationRequestRepository repo;

    @Autowired
    UserRepository users;

    private static NotificationRequest request(String externalId, String channel) {
        return request(externalId, channel, null);
    }

    private static NotificationRequest request(String externalId, String channel, Long userId) {
        NotificationRequest r = new NotificationRequest();
        r.setTitle("title");
        r.setBody("body");
        r.setPriority("NORMAL");
        r.setChannel(channel);
        r.setStatus("QUEUED");
        r.setExternalSource("test:item");
        r.setExternalId(externalId);
        r.setUserId(userId);
        return r;
    }

    private long newUser() {
        User u = new User();
        u.setExternalId("discord-" + UUID.randomUUID());
        return users.saveAndFlush(u).getId();
    }

    @Test
    void sameSourceIdAndChannelIsRejected() {
        repo.saveAndFlush(request("dup-1", "DISCORD"));

        // Second insert of the same (source, id, channel) must hit the unique index.
        // Neither row has a user, so this only holds because of NULLS NOT DISTINCT -
        // a plain unique index would treat the two NULLs as different and allow it.
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.saveAndFlush(request("dup-1", "DISCORD")));
    }

    @Test
    void sameSourceIdOnDifferentChannelIsAllowed() {
        // The index includes channel, so one source item can fan out to both
        repo.saveAndFlush(request("fanout-1", "DISCORD"));
        repo.saveAndFlush(request("fanout-1", "SLACK"));

        assertTrue(repo.findByExternalSourceAndExternalIdAndChannelAndUserId(
                "test:item", "fanout-1", "DISCORD", null).isPresent());
        assertTrue(repo.findByExternalSourceAndExternalIdAndChannelAndUserId(
                "test:item", "fanout-1", "SLACK", null).isPresent());
    }

    @Test
    void sameItemForTwoUsersIsAllowed() {
        // One concert matching two subscribers is two alerts, not a duplicate
        long alice = newUser();
        long bob = newUser();

        NotificationRequest forAlice = repo.saveAndFlush(request("concert-1", "DISCORD", alice));
        NotificationRequest forBob = repo.saveAndFlush(request("concert-1", "DISCORD", bob));

        assertNotEquals(forAlice.getId(), forBob.getId());
    }

    @Test
    void sameItemForTheSameUserIsRejected() {
        long alice = newUser();
        repo.saveAndFlush(request("concert-2", "DISCORD", alice));

        assertThrows(DataIntegrityViolationException.class,
                () -> repo.saveAndFlush(request("concert-2", "DISCORD", alice)));
    }

    @Test
    void lookupUsedByDuplicateHandlingFindsTheOriginalRow() {
        NotificationRequest saved = repo.saveAndFlush(request("lookup-1", "SLACK"));

        // Mirrors the recovery path in NotificationIntakeService.enqueue
        NotificationRequest found = repo.findByExternalSourceAndExternalIdAndChannelAndUserId(
                "test:item", "lookup-1", "SLACK", null).orElseThrow();

        assertEquals(saved.getId(), found.getId());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    void lookupTellsUsersApart() {
        long alice = newUser();
        long bob = newUser();
        NotificationRequest forNobody = repo.saveAndFlush(request("lookup-2", "DISCORD"));
        repo.saveAndFlush(request("lookup-2", "DISCORD", alice));
        NotificationRequest forBob = repo.saveAndFlush(request("lookup-2", "DISCORD", bob));

        // Three rows share (source, id, channel); the user has to pick exactly one, or
        // the Optional-returning lookup would throw on a non-unique result
        assertEquals(forBob.getId(), repo.findByExternalSourceAndExternalIdAndChannelAndUserId(
                "test:item", "lookup-2", "DISCORD", bob).orElseThrow().getId());

        // A null user must mean "user_id IS NULL", not "any user" or "user_id = NULL"
        // (which matches nothing) - the REST API's duplicate handling depends on it
        assertEquals(forNobody.getId(), repo.findByExternalSourceAndExternalIdAndChannelAndUserId(
                "test:item", "lookup-2", "DISCORD", null).orElseThrow().getId());
    }
}
