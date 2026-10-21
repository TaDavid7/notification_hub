package com.david.notification_hub.polling;

import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.feed.FeedRepository;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.Source;
import com.david.notification_hub.source.SourceException;
import com.david.notification_hub.source.SourceRegistry;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.source.ratelimit.RateLimitExceededException;
import com.david.notification_hub.support.AbstractPostgresTest;
import com.david.notification_hub.user.User;
import com.david.notification_hub.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The poller against Postgres, with the real sources swapped for a scripted one that
 * records each call.
 *
 * poll_groups is shared by everything, and other test classes leave feeds behind, so
 * each test starts from no feeds and no groups.
 */
@SpringBootTest(properties = "polling.rateLimits[limited.test].perDay=1")
class PollSchedulerTest extends AbstractPostgresTest {

    @Autowired PollScheduler scheduler;
    @Autowired PollStore store;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestClient.Builder restClients;

    @MockitoBean SourceRegistry registry;

    private final ScriptedSource source = new ScriptedSource();

    @BeforeEach
    void startEmpty() {
        jdbc.update("DELETE FROM feeds");
        jdbc.update("DELETE FROM poll_groups");
        when(registry.types()).thenReturn(Set.of(SourceType.TICKETMASTER, SourceType.CHEAPSHARK));
        when(registry.get(any())).thenReturn(source);
    }

    // ---------- grouping ----------

    @Test
    void identicalQueriesAreOneGroupAndOneCall() {
        Map<String, String> sf = query("sf");
        Feed alice = feed(newUser(), sf);
        Feed bob = feed(newUser(), sf);
        feed(newUser(), query("nyc"));

        assertEquals(2, scheduler.pollDue());

        assertEquals(2, source.calls.size(), "one call per distinct query, not per subscriber");
        assertEquals(2, count("SELECT count(*) FROM poll_groups"));
        // Both of the shared query's feeds took part in the one poll
        assertEquals(1, links(alice));
        assertEquals(1, links(bob));
    }

    @Test
    void aGroupGoesWhenItsLastFeedDoes() {
        Feed only = feed(newUser(), query("sf"));
        scheduler.pollDue();

        feeds.deleteById(only.getId());
        scheduler.pollDue();

        assertEquals(0, count("SELECT count(*) FROM poll_groups"));
    }

    // ---------- scheduling ----------

    @Test
    void aGroupIsNotPolledAgainUntilItsIntervalHasPassed() {
        feed(newUser(), query("sf"));

        scheduler.pollDue();
        scheduler.pollDue();
        assertEquals(1, source.calls.size());

        OffsetDateTime next = nextPollAt();
        assertTrue(next.isAfter(OffsetDateTime.now().plusMinutes(50)), "about an hour out, give or take 10%");
        assertTrue(next.isBefore(OffsetDateTime.now().plusMinutes(70)));

        makeDue();
        scheduler.pollDue();
        assertEquals(2, source.calls.size());
    }

    @Test
    void aClaimedGroupIsSkippedByAnyoneElseUntilTheLeaseEnds() {
        // What a second instance sees while the first is mid-poll, or after it crashed
        feed(newUser(), query("sf"));
        store.syncGroups();

        List<PollGroup> first = store.claimDue(registry.types(), Duration.ofMinutes(10), 10);
        List<PollGroup> second = store.claimDue(registry.types(), Duration.ofMinutes(10), 10);

        assertEquals(1, first.size());
        assertEquals(0, second.size());

        makeDue();   // the lease running out
        assertEquals(1, store.claimDue(registry.types(), Duration.ofMinutes(10), 10).size());
    }

    @Test
    void onlySourcesThatExistArePolled() {
        when(registry.types()).thenReturn(Set.of(SourceType.TICKETMASTER));
        Feed deal = feed(newUser(), query("deal"));
        deal.setSourceType(SourceType.CHEAPSHARK);
        feeds.save(deal);

        assertEquals(0, scheduler.pollDue());
        assertEquals(0, source.calls.size());
    }

    // ---------- failures ----------

    @Test
    void aFailedPollBacksOffAndSaysWhy() {
        feed(newUser(), query("sf"));
        source.respond = f -> { throw new SourceException(SourceType.TICKETMASTER, "HTTP 500: oops"); };

        scheduler.pollDue();

        Map<String, Object> group = group();
        assertEquals(1, group.get("failures"));
        assertTrue(((String) group.get("last_error")).contains("HTTP 500"));
        // Base backoff is 5 minutes, jittered down to no less than half
        assertTrue(nextPollAt().isAfter(OffsetDateTime.now().plusMinutes(2)));
    }

    @Test
    void theApisRetryAfterIsRespectedWhenLongerThanTheBackoff() {
        feed(newUser(), query("sf"));
        source.respond = f -> { throw tooManyRequests(Duration.ofHours(2)); };

        scheduler.pollDue();

        assertTrue(nextPollAt().isAfter(OffsetDateTime.now().plusMinutes(119)));
    }

    @Test
    void ourOwnRateLimitDefersWithoutCountingAFailure() {
        feed(newUser(), query("sf"));
        source.respond = f -> { throw throttled(); };

        scheduler.pollDue();

        assertEquals(0, group().get("failures"));
        assertTrue(nextPollAt().isAfter(OffsetDateTime.now().plusMinutes(29)));
    }

    @Test
    void aSuccessClearsEarlierFailures() {
        feed(newUser(), query("sf"));
        source.respond = f -> { throw new SourceException(SourceType.TICKETMASTER, "HTTP 503"); };
        scheduler.pollDue();

        source.respond = f -> List.of();
        makeDue();
        scheduler.pollDue();

        assertEquals(0, group().get("failures"));
        assertNull(group().get("last_error"));
    }

    @Test
    void oneBrokenGroupDoesNotStopTheOthers() {
        feed(newUser(), query("broken"));
        Feed fine = feed(newUser(), query("fine"));
        source.respond = f -> {
            if (f.getParams().get("area").startsWith("broken")) throw new IllegalArgumentException("feed param 'lat' is missing");
            return List.of(show(f));
        };

        assertEquals(2, scheduler.pollDue());

        assertEquals(1, links(fine));
        assertEquals(1, count("SELECT count(*) FROM poll_groups WHERE failures = 1"));
    }

    // ---------- end to end ----------

    @Test
    void aNewSubscriptionsFirstPollSendsItsSummary() {
        User alice = newUser();
        Feed f = feed(alice, query("sf"));

        scheduler.pollDue();

        assertEquals(List.of("feed-" + f.getId() + ":baseline"), jdbc.queryForList(
                "SELECT external_id FROM notification_requests WHERE user_id = ?", String.class, alice.getId()));
    }

    // ---------- rate limit wiring ----------

    @Test
    void springsRestClientsCarryTheConfiguredRateLimits() {
        // Set by this class's @SpringBootTest properties - which also shows a host with
        // dots binds when bracketed
        RestClient.Builder builder = restClients.baseUrl("https://limited.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://limited.test/a")).andRespond(withSuccess());
        RestClient client = builder.build();

        client.get().uri("/a").retrieve().toBodilessEntity();
        ResourceAccessException refused = assertThrows(ResourceAccessException.class,
                () -> client.get().uri("/b").retrieve().toBodilessEntity());

        assertInstanceOf(RateLimitExceededException.class,
                refused.getMostSpecificCause());
    }

    // ---------- helpers ----------

    /** Plays any source: answers with whatever {@link #respond} says, and remembers each call. */
    private static final class ScriptedSource implements Source {
        final List<Map<String, String>> calls = new ArrayList<>();
        Function<Feed, List<Event>> respond = f -> List.of(show(f));

        @Override public SourceType type() { return SourceType.TICKETMASTER; }

        @Override public List<Event> fetch(Feed feed) {
            calls.add(feed.getParams());
            return respond.apply(feed);
        }
    }

    // One event per query, typed to match the feed so the change detector accepts it
    private static Event show(Feed f) {
        return new Event(f.getSourceType(), "show-" + f.getParams().get("area"), "Band Live",
                OffsetDateTime.now().plusDays(30), null, null, new BigDecimal("49.50"), null, EventStatus.SCHEDULED);
    }

    private static SourceException tooManyRequests(Duration retryAfter) {
        return SourceException.from(SourceType.TICKETMASTER,
                new HttpClientErrorException(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        retryAfterHeader(retryAfter), new byte[0], null));
    }

    private static HttpHeaders retryAfterHeader(Duration d) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, String.valueOf(d.toSeconds()));
        return h;
    }

    private static SourceException throttled() {
        return SourceException.from(SourceType.TICKETMASTER, new ResourceAccessException("I/O error",
                new RateLimitExceededException("app.ticketmaster.com",
                        Duration.ofMinutes(30))));
    }

    private Map<String, String> query(String area) {
        return new HashMap<>(Map.of("area", area + "-" + UUID.randomUUID()));
    }

    private User newUser() {
        User u = new User();
        u.setExternalId(String.valueOf(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE));
        return users.save(u);
    }

    private Feed feed(User u, Map<String, String> params) {
        Feed f = new Feed();
        f.setUserId(u.getId());
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(new HashMap<>(params));
        return feeds.save(f);
    }

    private Map<String, Object> group() {
        return jdbc.queryForMap("SELECT * FROM poll_groups");
    }

    private OffsetDateTime nextPollAt() {
        return jdbc.queryForObject("SELECT next_poll_at FROM poll_groups", OffsetDateTime.class);
    }

    private void makeDue() {
        jdbc.update("UPDATE poll_groups SET next_poll_at = now() - interval '1 second'");
    }

    private int links(Feed f) {
        return count("SELECT count(*) FROM feed_events WHERE feed_id = " + f.getId());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
