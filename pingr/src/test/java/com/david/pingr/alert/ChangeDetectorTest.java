package com.david.pingr.alert;

import com.david.pingr.alert.ChangeDetector.Summary;
import com.david.pingr.feed.Feed;
import com.david.pingr.feed.FeedRepository;
import com.david.pingr.notification_request.NotificationIntakeService;
import com.david.pingr.source.Event;
import com.david.pingr.source.EventStatus;
import com.david.pingr.source.SourceType;
import com.david.pingr.support.AbstractPostgresTest;
import com.david.pingr.user.User;
import com.david.pingr.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Change detection against a real database: what gets stored, who gets told, and that
 * the two always agree.
 *
 * The intake service is a spy - real unless a test says otherwise - so one test can make
 * the outbox fail and show the event change rolling back with it.
 *
 * Each test makes its own users, feeds and event id, and reads back only its own rows.
 */
@SpringBootTest
class ChangeDetectorTest extends AbstractPostgresTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2030-06-01T03:00:00Z");

    @Autowired ChangeDetector detector;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired JdbcTemplate jdbc;

    @MockitoSpyBean NotificationIntakeService intake;

    private String id;

    @BeforeEach
    void newEventId() {
        id = "tm-" + UUID.randomUUID();
    }

    // ---------- new ----------

    @Test
    void aNewEventAlertsEachSubscriberInTheGroupOnce() {
        User alice = newUser();
        User bob = newUser();

        Summary s = detector.apply(group("A", alice, bob), List.of(concert("49.50")));

        assertEquals(new Summary(1, 1, 0, 2, 0, 0), s);
        assertEquals(List.of(id + ":new"), keysFor(alice));
        assertEquals(List.of(id + ":new"), keysFor(bob));

        Map<String, Object> row = rowsFor(alice).get(0);
        assertEquals("ticketmaster", row.get("external_source"));
        assertEquals("DISCORD", row.get("channel"));
        assertEquals("QUEUED", row.get("status"));
        assertEquals("New: Band Live", row.get("title"));
        assertTrue(((String) row.get("body")).startsWith("<@" + alice.getExternalId() + ">"));
    }

    @Test
    void pollingTheSameThingAgainAlertsNobody() {
        User alice = newUser();
        List<Feed> group = group("A", alice);

        detector.apply(group, List.of(concert("49.50")));
        Summary again = detector.apply(group, List.of(concert("49.50")));

        assertEquals(new Summary(1, 0, 0, 0, 0, 0), again);
        assertEquals(List.of(id + ":new"), keysFor(alice));
    }

    @Test
    void anEventFirstSeenCancelledIsStoredButNotAnnounced() {
        User alice = newUser();

        detector.apply(group("A", alice), List.of(concert("49.50", EventStatus.CANCELED, "Band Live")));

        assertEquals(List.of(), keysFor(alice));
        assertEquals(1, storedCount(), "still stored, so a later un-cancel is a change");
    }

    // ---------- updates ----------

    @Test
    void aChangeIsOneUpdatePerSubscriberSayingWhatChanged() {
        User alice = newUser();
        User bob = newUser();
        List<Feed> group = group("A", alice, bob);

        detector.apply(group, List.of(concert("49.50")));
        Summary s = detector.apply(group, List.of(concert("39.50")));

        assertEquals(new Summary(1, 0, 1, 2, 0, 0), s);
        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(alice));
        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(bob));

        Map<String, Object> update = rowsFor(alice).get(1);
        assertEquals("Price drop: Band Live", update.get("title"));
        assertTrue(((String) update.get("body")).contains("$49.50 → $39.50"));

        Map<String, Object> stored = stored();
        assertEquals(0, new BigDecimal("39.50").compareTo((BigDecimal) stored.get("price")));
        assertEquals(1, stored.get("revision"));
    }

    @Test
    void everyChangeAlertsEvenWhenAPriceComesBack() {
        User alice = newUser();
        List<Feed> group = group("A", alice);

        for (String price : List.of("49.50", "39.50", "49.50", "39.50")) {
            detector.apply(group, List.of(concert(price)));
        }

        assertEquals(List.of(id + ":new", id + ":r1", id + ":r2", id + ":r3"), keysFor(alice));
    }

    @Test
    void aRetitleIsKeptButNotAlerted() {
        User alice = newUser();
        List<Feed> group = group("A", alice);

        detector.apply(group, List.of(concert("49.50")));
        detector.apply(group, List.of(concert("49.50", EventStatus.SCHEDULED, "Band Live (Late Show)")));

        assertEquals(List.of(id + ":new"), keysFor(alice));
        assertEquals("Band Live (Late Show)", stored().get("title"));
        assertEquals(0, stored().get("revision"));
    }

    @Test
    void aCancellationIsAnnouncedAsSuch() {
        User alice = newUser();
        List<Feed> group = group("A", alice);

        detector.apply(group, List.of(concert("49.50")));
        detector.apply(group, List.of(concert("49.50", EventStatus.CANCELED, "Band Live")));

        assertEquals("Canceled: Band Live", rowsFor(alice).get(1).get("title"));
    }

    // ---------- across groups ----------

    @Test
    void aSecondGroupFindingAKnownEventTellsItsOwnSubscribers() {
        // Overlapping areas: two groups, one concert. Whichever polls second has to tell
        // its own people, even though the event is no longer new to the database.
        User alice = newUser();
        User bob = newUser();

        detector.apply(group("A", alice), List.of(concert("49.50")));
        detector.apply(group("B", bob), List.of(concert("49.50")));

        assertEquals(List.of(id + ":new"), keysFor(alice));
        assertEquals(List.of(id + ":new"), keysFor(bob));
    }

    @Test
    void aChangeSeenByOneGroupReachesFollowersFromEveryGroup() {
        User alice = newUser();
        User bob = newUser();
        List<Feed> a = group("A", alice);
        List<Feed> b = group("B", bob);
        detector.apply(a, List.of(concert("49.50")));
        detector.apply(b, List.of(concert("49.50")));

        detector.apply(a, List.of(concert("39.50")));
        // B's next poll sees the already-stored price: no change, and nothing re-sent
        detector.apply(b, List.of(concert("39.50")));

        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(alice));
        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(bob), "bob hears it from A's poll");
    }

    @Test
    void someoneFollowingThroughTwoFeedsHearsEachThingOnce() {
        User alice = newUser();
        List<Feed> near = group("near", alice);
        List<Feed> wide = group("wide", alice);

        detector.apply(near, List.of(concert("49.50")));
        detector.apply(wide, List.of(concert("49.50")));
        detector.apply(near, List.of(concert("39.50")));

        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(alice));
    }

    @Test
    void anUnsubscribedUserHearsNoMore() {
        User alice = newUser();
        User bob = newUser();
        List<Feed> group = group("A", alice, bob);
        detector.apply(group, List.of(concert("49.50")));

        feeds.deleteById(group.get(1).getId());
        detector.apply(List.of(group.get(0)), List.of(concert("39.50")));

        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(alice));
        assertEquals(List.of(id + ":new"), keysFor(bob));
    }

    // ---------- robustness ----------

    @Test
    void anEventListedTwiceInOneFetchIsHandledOnce() {
        User alice = newUser();

        Summary s = detector.apply(group("A", alice), List.of(concert("49.50"), concert("49.50")));

        assertEquals(1, s.created());
        assertEquals(List.of(id + ":new"), keysFor(alice));
    }

    @Test
    void anEventFromTheWrongSourceIsRefusedWithoutStoppingTheRest() {
        User alice = newUser();
        Event stray = new Event(SourceType.CHEAPSHARK, "game-" + id, "Hades", null, null, null,
                new BigDecimal("9.99"), null, null);

        Summary s = detector.apply(group("A", alice), List.of(stray, concert("49.50")));

        assertEquals(1, s.failed());
        assertEquals(1, s.created());
        assertEquals(List.of(id + ":new"), keysFor(alice));
    }

    @Test
    void aFailedAlertRollsTheChangeBackSoTheNextPollRetriesIt() {
        User alice = newUser();
        List<Feed> group = group("A", alice);
        detector.apply(group, List.of(concert("49.50")));

        doThrow(new RuntimeException("outbox unavailable"))
                .when(intake).enqueueIfAbsent(any(), any(), any(), any(), any(), any());
        Summary failed = detector.apply(group, List.of(concert("39.50")));

        assertEquals(1, failed.failed());
        // The price change was not kept without its alert...
        assertEquals(0, new BigDecimal("49.50").compareTo((BigDecimal) stored().get("price")));
        assertEquals(0, stored().get("revision"));
        assertEquals(List.of(id + ":new"), keysFor(alice));

        // ...so the next poll sees the same change and this time it goes out
        reset(intake);
        detector.apply(group, List.of(concert("39.50")));

        assertEquals(List.of(id + ":new", id + ":r1"), keysFor(alice));
    }

    // ---------- a feed's first poll ----------

    @Test
    void aFeedsFirstPollIsOneSummaryNotAnAlertPerItem() {
        User alice = newUser();
        List<Feed> group = freshGroup("A", alice);
        List<Event> alreadyOnSale = List.of(
                concert(id + "-1", "40.00", STARTS), concert(id + "-2", "50.00", STARTS.plusDays(1)),
                concert(id + "-3", "60.00", STARTS.plusDays(2)));

        Summary s = detector.apply(group, alreadyOnSale);

        assertEquals(1, s.baselined());
        assertEquals(List.of("feed-" + group.get(0).getId() + ":baseline"), keysFor(alice));
        assertTrue(((String) rowsFor(alice).get(0).get("body")).contains("3 shows right now"));
        assertEquals(3, linksOf(group.get(0)), "linked, so changes and reminders still reach alice");
        assertNotNull(baselinedAt(group.get(0)));
    }

    @Test
    void afterTheFirstPollOnlyGenuinelyNewItemsAreAnnounced() {
        User alice = newUser();
        List<Feed> group = freshGroup("A", alice);
        detector.apply(group, List.of(concert(id + "-1", "40.00", STARTS)));

        // The poller reloads feeds every pass; this one is now past its first poll
        List<Feed> reloaded = List.of(feeds.findById(group.get(0).getId()).orElseThrow());
        detector.apply(reloaded, List.of(concert(id + "-1", "40.00", STARTS), concert(id + "-2", "50.00", STARTS)));

        assertEquals(List.of("feed-" + group.get(0).getId() + ":baseline", id + "-2:new"), keysFor(alice));
    }

    @Test
    void changesToWhatTheFirstPollFoundAreStillAnnounced() {
        User alice = newUser();
        List<Feed> group = freshGroup("A", alice);
        detector.apply(group, List.of(concert(id + "-1", "40.00", STARTS)));

        List<Feed> reloaded = List.of(feeds.findById(group.get(0).getId()).orElseThrow());
        detector.apply(reloaded, List.of(concert(id + "-1", "30.00", STARTS)));

        assertEquals(List.of("feed-" + group.get(0).getId() + ":baseline", id + "-1:r1"), keysFor(alice));
    }

    @Test
    void aFirstPollThatFindsNothingStillFinishes() {
        User alice = newUser();
        List<Feed> group = freshGroup("A", alice);

        detector.apply(group, List.of());

        assertTrue(((String) rowsFor(alice).get(0).get("body")).contains("Nothing matches right now"));
        assertNotNull(baselinedAt(group.get(0)), "or the next real item would be swallowed as baseline");
    }

    @Test
    void aNewSubscriberToAQueryOthersAlreadyHaveGetsASummaryAndTheOthersNothing() {
        User alice = newUser();
        User bob = newUser();
        Map<String, String> shared = new HashMap<>(Map.of("area", "shared-" + id));
        Feed alicesFeed = feed(alice, shared, OffsetDateTime.now().minusDays(1));
        detector.apply(List.of(alicesFeed), List.of(concert("49.50")));

        Feed bobsFeed = feed(bob, shared, null);
        detector.apply(List.of(alicesFeed, bobsFeed), List.of(concert("49.50")));

        assertEquals(List.of(id + ":new"), keysFor(alice));
        assertEquals(List.of("feed-" + bobsFeed.getId() + ":baseline"), keysFor(bob));
    }

    @Test
    void theSummaryCannotBeUsedToPingTheChannel() {
        // The summary repeats the subscription, which is what the user typed
        User mallory = newUser();
        Feed f = feed(mallory, new HashMap<>(Map.of("title", "@everyone", "maxPrice", "1.00")), null);
        f.setSourceType(SourceType.CHEAPSHARK);
        f = feeds.save(f);

        detector.apply(List.of(f), List.of());

        Map<String, Object> row = rowsFor(mallory).get(0);
        assertFalse(((String) row.get("title")).contains("@everyone"));
        assertFalse(((String) row.get("body")).contains("@everyone"));
    }

    // ---------- helpers ----------

    private Feed feed(User u, Map<String, String> params, OffsetDateTime baselinedAt) {
        Feed f = new Feed();
        f.setUserId(u.getId());
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(params);
        f.setBaselinedAt(baselinedAt);
        return feeds.save(f);
    }

    private Event concert(String price) {
        return concert(price, EventStatus.SCHEDULED, "Band Live");
    }

    private Event concert(String price, EventStatus status, String title) {
        return new Event(SourceType.TICKETMASTER, id, title, STARTS, null, "The Fillmore, San Francisco",
                new BigDecimal(price), "https://example.com/" + id, status);
    }

    private User newUser() {
        User u = new User();
        // Shaped like a Discord snowflake
        u.setExternalId(String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000_000_000L, 999_999_999_999_999_999L)));
        return users.save(u);
    }

    /**
     * One feed per user, all with the same params - a group, as the poller will hand them
     * over. Past their first poll, so items are announced one by one.
     */
    private List<Feed> group(String area, User... members) {
        return group(area, OffsetDateTime.now().minusDays(1), members);
    }

    /** As {@link #group}, but just subscribed: the next poll is their first. */
    private List<Feed> freshGroup(String area, User... members) {
        return group(area, null, members);
    }

    private List<Feed> group(String area, OffsetDateTime baselinedAt, User... members) {
        Map<String, String> params = new HashMap<>();
        params.put("area", area + "-" + id);
        return Arrays.stream(members).map(u -> {
            Feed f = new Feed();
            f.setUserId(u.getId());
            f.setSourceType(SourceType.TICKETMASTER);
            f.setParams(new HashMap<>(params));
            f.setBaselinedAt(baselinedAt);
            return feeds.save(f);
        }).toList();
    }

    private Event concert(String externalId, String price, OffsetDateTime startsAt) {
        return new Event(SourceType.TICKETMASTER, externalId, "Show " + externalId, startsAt, null, null,
                new BigDecimal(price), "https://example.com/" + externalId, null);
    }

    private OffsetDateTime baselinedAt(Feed f) {
        return jdbc.queryForObject("SELECT baselined_at FROM feeds WHERE id = ?", OffsetDateTime.class, f.getId());
    }

    private int linksOf(Feed f) {
        return jdbc.queryForObject("SELECT count(*) FROM feed_events WHERE feed_id = ?", Integer.class, f.getId());
    }

    private List<String> keysFor(User u) {
        return jdbc.queryForList(
                "SELECT external_id FROM notification_requests WHERE user_id = ? ORDER BY id", String.class, u.getId());
    }

    private List<Map<String, Object>> rowsFor(User u) {
        return jdbc.queryForList(
                "SELECT * FROM notification_requests WHERE user_id = ? ORDER BY id", u.getId());
    }

    private Map<String, Object> stored() {
        return jdbc.queryForMap("SELECT * FROM events WHERE source = 'TICKETMASTER' AND external_id = ?", id);
    }

    private int storedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM events WHERE external_id = ?", Integer.class, id);
    }
}
