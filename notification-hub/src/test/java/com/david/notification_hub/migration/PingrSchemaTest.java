package com.david.notification_hub.migration;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.event.StoredEventRepository;
import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.feed.FeedRepository;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.support.AbstractPostgresTest;
import com.david.notification_hub.user.User;
import com.david.notification_hub.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the tables V8__pingr_schema.sql adds: the constraints later steps rely on,
 * and the jsonb behaviour that lets identical queries share one API call.
 *
 * Every test makes its own users and ids rather than cleaning tables, since the
 * database is shared with the other context tests.
 */
@SpringBootTest
class PingrSchemaTest extends AbstractPostgresTest {

    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired StoredEventRepository events;
    @Autowired JdbcTemplate jdbc;

    // ---------- events ----------

    @Test
    void anItemIsStoredOncePerSource() {
        String id = UUID.randomUUID().toString();
        events.saveAndFlush(event(SourceType.TICKETMASTER, id));

        assertThrows(DataIntegrityViolationException.class,
                () -> events.saveAndFlush(event(SourceType.TICKETMASTER, id)));
    }

    @Test
    void theSameIdFromAnotherSourceIsADifferentItem() {
        String id = UUID.randomUUID().toString();
        events.saveAndFlush(event(SourceType.TICKETMASTER, id));
        events.saveAndFlush(event(SourceType.CHEAPSHARK, id));
    }

    @Test
    void anEventRoundTrips() {
        StoredEvent e = event(SourceType.TICKETMASTER, UUID.randomUUID().toString());
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 12, 31, 20, 0, 0, 0, ZoneOffset.UTC);
        e.setStartsAt(startsAt);
        e.setVenue("The Fillmore");
        e.setPrice(new BigDecimal("49.50"));
        e.setUrl("https://example.com/e/1");
        long id = events.saveAndFlush(e).getId();

        StoredEvent back = events.findById(id).orElseThrow();
        assertEquals(SourceType.TICKETMASTER, back.getSource());
        assertTrue(startsAt.isEqual(back.getStartsAt()));
        assertNull(back.getOnSaleAt(), "a source with no on-sale date leaves it null");
        assertEquals("The Fillmore", back.getVenue());
        assertEquals(0, new BigDecimal("49.50").compareTo(back.getPrice()));
        assertNotNull(back.getFirstSeenAt());
        assertNotNull(back.getUpdatedAt());
    }

    // ---------- feeds ----------

    @Test
    void subscribingTwiceToTheSameQueryIsRejectedWhateverTheKeyOrder() {
        long alice = newUser();
        feeds.saveAndFlush(feed(alice, ordered("lat", "37.77", "radius", "25")));

        // jsonb normalises key order, so this is the same query, not a new one
        assertThrows(DataIntegrityViolationException.class,
                () -> feeds.saveAndFlush(feed(alice, ordered("radius", "25", "lat", "37.77"))));
    }

    @Test
    void feedsWithEqualParamsFormOneGroup() {
        // Polling depends on this: two users asking the same thing are one API call
        long alice = newUser();
        long bob = newUser();
        long a = feeds.saveAndFlush(feed(alice, ordered("lat", "37.77", "radius", "25"))).getId();
        long b = feeds.saveAndFlush(feed(bob, ordered("radius", "25", "lat", "37.77"))).getId();
        long c = feeds.saveAndFlush(feed(bob, ordered("lat", "37.77", "radius", "50"))).getId();

        List<Long> groupSizes = jdbc.queryForList("""
                SELECT count(*) FROM feeds
                 WHERE id IN (?, ?, ?)
                 GROUP BY source_type, params
                 ORDER BY count(*) DESC
                """, Long.class, a, b, c);

        assertEquals(List.of(2L, 1L), groupSizes);
    }

    @Test
    void paramsRoundTrip() {
        Map<String, String> params = ordered("title", "hades", "maxPrice", "20");
        long id = feeds.saveAndFlush(feed(newUser(), params)).getId();

        assertEquals(params, feeds.findById(id).orElseThrow().getParams());
    }

    // ---------- cascades ----------

    @Test
    void deletingAFeedRemovesItsLinksButNotTheEvent() {
        long feedId = feeds.saveAndFlush(feed(newUser(), ordered("lat", "1", "radius", "5"))).getId();
        long eventId = events.saveAndFlush(event(SourceType.TICKETMASTER, UUID.randomUUID().toString())).getId();
        link(feedId, eventId);

        feeds.deleteById(feedId);

        assertEquals(0, linksTo(eventId), "an unsubscribe must not leave links to a missing feed");
        assertTrue(events.existsById(eventId), "other feeds may still match the event");
    }

    @Test
    void deletingAUserRemovesTheirFeeds() {
        long alice = newUser();
        long feedId = feeds.saveAndFlush(feed(alice, ordered("lat", "2", "radius", "5"))).getId();

        users.deleteById(alice);

        assertFalse(feeds.existsById(feedId));
    }

    // ---------- helpers ----------

    private long newUser() {
        User u = new User();
        u.setExternalId("discord-" + UUID.randomUUID());
        return users.saveAndFlush(u).getId();
    }

    private static Feed feed(long userId, Map<String, String> params) {
        Feed f = new Feed();
        f.setUserId(userId);
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(params);
        return f;
    }

    private static StoredEvent event(SourceType source, String externalId) {
        StoredEvent e = new StoredEvent();
        e.setSource(source);
        e.setExternalId(externalId);
        e.setTitle("title");
        e.setContentHash("hash");
        return e;
    }

    /** Insertion-ordered, so a test controls the key order the JSON is written in. */
    private static Map<String, String> ordered(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private void link(long feedId, long eventId) {
        jdbc.update("INSERT INTO feed_events (feed_id, event_id) VALUES (?, ?)", feedId, eventId);
    }

    private long linksTo(long eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM feed_events WHERE event_id = ?", Long.class, eventId);
    }
}
