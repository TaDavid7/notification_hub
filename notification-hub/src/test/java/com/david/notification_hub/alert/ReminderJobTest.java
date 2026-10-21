package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.event.StoredEventRepository;
import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.feed.FeedRepository;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.support.AbstractPostgresTest;
import com.david.notification_hub.user.User;
import com.david.notification_hub.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reminders against a real database, with the job run at a chosen moment.
 *
 * Every test gets its own "now" a week past the last, so no test's events fall inside
 * another's windows - the windows are at most a day.
 */
@SpringBootTest
class ReminderJobTest extends AbstractPostgresTest {

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2031-01-01T12:00:00Z");
    private static final AtomicInteger WEEK = new AtomicInteger();

    @Autowired ReminderJob job;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired StoredEventRepository events;
    @Autowired JdbcTemplate jdbc;

    private OffsetDateTime now;

    @BeforeEach
    void pickAMoment() {
        now = BASE.plusWeeks(WEEK.incrementAndGet());
    }

    // ---------- starting soon ----------

    @Test
    void anEventStartingWithinADayIsRemindedToEachFollowerOnce() {
        User alice = newUser();
        User bob = newUser();
        StoredEvent e = event(now.plusHours(3), null, EventStatus.SCHEDULED);
        follow(alice, e);
        follow(bob, e);

        assertEquals(2, job.runAt(now));
        assertEquals(0, job.runAt(now.plusMinutes(15)), "the next pass must not repeat it");

        String key = e.getExternalId() + ":soon:" + e.getStartsAt().toEpochSecond();
        assertEquals(List.of(key), keysFor(alice));
        assertEquals(List.of(key), keysFor(bob));
    }

    @Test
    void theReminderSaysWhenAndWhere() {
        User alice = newUser();
        StoredEvent e = event(now.plusHours(3), null, EventStatus.SCHEDULED);
        follow(alice, e);

        job.runAt(now);

        Map<String, Object> row = rowsFor(alice).get(0);
        assertEquals("Starting soon: Band Live", row.get("title"));
        String body = (String) row.get("body");
        assertTrue(body.startsWith("<@" + alice.getExternalId() + ">"));
        assertTrue(body.contains("<t:" + e.getStartsAt().toEpochSecond() + ":R>"));
        assertTrue(body.contains("The Fillmore, San Francisco"));
    }

    @Test
    void outsideTheWindowNothingIsSent() {
        User alice = newUser();
        follow(alice, event(now.plusHours(30), null, EventStatus.SCHEDULED));   // too far off
        follow(alice, event(now.minusHours(1), null, EventStatus.SCHEDULED));   // already started

        assertEquals(0, job.runAt(now));
        assertEquals(List.of(), keysFor(alice));
    }

    @Test
    void onlyEventsStillGoingAheadAreReminded() {
        User alice = newUser();
        follow(alice, event(now.plusHours(2), null, EventStatus.CANCELED));
        follow(alice, event(now.plusHours(2), null, EventStatus.POSTPONED));
        StoredEvent moved = event(now.plusHours(2), null, EventStatus.RESCHEDULED);
        follow(alice, moved);

        job.runAt(now);

        assertEquals(List.of(moved.getExternalId() + ":soon:" + moved.getStartsAt().toEpochSecond()), keysFor(alice));
    }

    @Test
    void aRescheduledEventIsRemindedAgainForItsNewTime() {
        User alice = newUser();
        StoredEvent e = event(now.plusHours(3), null, EventStatus.SCHEDULED);
        follow(alice, e);
        job.runAt(now);

        OffsetDateTime later = now.plusHours(6);
        jdbc.update("UPDATE events SET starts_at = ? WHERE id = ?", later, e.getId());
        job.runAt(now.plusMinutes(15));

        assertEquals(List.of(
                e.getExternalId() + ":soon:" + now.plusHours(3).toEpochSecond(),
                e.getExternalId() + ":soon:" + later.toEpochSecond()), keysFor(alice));
    }

    @Test
    void anEventNobodyFollowsIsNotReminded() {
        StoredEvent e = event(now.plusHours(3), null, EventStatus.SCHEDULED);

        assertEquals(0, job.runAt(now));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM notification_requests WHERE external_id LIKE ?", Integer.class,
                e.getExternalId() + "%"));
    }

    // ---------- on sale soon ----------

    @Test
    void goingOnSaleWithinTheHourIsReminded() {
        User alice = newUser();
        StoredEvent soon = event(now.plusDays(30), now.plusMinutes(30), EventStatus.SCHEDULED);
        StoredEvent later = event(now.plusDays(30), now.plusHours(2), EventStatus.SCHEDULED);
        follow(alice, soon);
        follow(alice, later);

        job.runAt(now);

        assertEquals(List.of(soon.getExternalId() + ":onsale:" + soon.getOnSaleAt().toEpochSecond()), keysFor(alice));
        assertEquals("On sale soon: Band Live", rowsFor(alice).get(0).get("title"));
    }

    // ---------- helpers ----------

    private StoredEvent event(OffsetDateTime startsAt, OffsetDateTime onSaleAt, EventStatus status) {
        StoredEvent e = new StoredEvent();
        e.setSource(SourceType.TICKETMASTER);
        e.setExternalId("tm-" + UUID.randomUUID());
        e.setTitle("Band Live");
        e.setStartsAt(startsAt);
        e.setOnSaleAt(onSaleAt);
        e.setVenue("The Fillmore, San Francisco");
        e.setStatus(status);
        e.setContentHash("hash");
        return events.save(e);
    }

    private User newUser() {
        User u = new User();
        u.setExternalId(String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())));
        return users.save(u);
    }

    // Gives the user a feed of their own and links it to the event, as a poll would
    private void follow(User u, StoredEvent e) {
        Map<String, String> params = new HashMap<>();
        params.put("area", UUID.randomUUID().toString());
        Feed f = new Feed();
        f.setUserId(u.getId());
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(params);
        f = feeds.save(f);
        jdbc.update("INSERT INTO feed_events (feed_id, event_id) VALUES (?, ?)", f.getId(), e.getId());
    }

    private List<String> keysFor(User u) {
        return jdbc.queryForList(
                "SELECT external_id FROM notification_requests WHERE user_id = ? ORDER BY id", String.class, u.getId());
    }

    private List<Map<String, Object>> rowsFor(User u) {
        return jdbc.queryForList("SELECT * FROM notification_requests WHERE user_id = ? ORDER BY id", u.getId());
    }
}
