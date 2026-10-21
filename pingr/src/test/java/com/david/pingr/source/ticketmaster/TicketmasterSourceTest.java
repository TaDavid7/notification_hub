package com.david.pingr.source.ticketmaster;

import com.david.pingr.feed.Feed;
import com.david.pingr.source.Event;
import com.david.pingr.source.EventStatus;
import com.david.pingr.source.SourceException;
import com.david.pingr.source.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.david.pingr.source.ticketmaster.TicketmasterSource.CLASSIFICATION;
import static com.david.pingr.source.ticketmaster.TicketmasterSource.LAT;
import static com.david.pingr.source.ticketmaster.TicketmasterSource.LON;
import static com.david.pingr.source.ticketmaster.TicketmasterSource.RADIUS;
import static com.david.pingr.source.ticketmaster.TicketmasterSource.SEGMENT;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Ticketmaster is played by MockRestServiceServer, so these run without a key or a
 * network. The response shapes follow the Discovery API's documented event search.
 */
class TicketmasterSourceTest {

    private static final String BASE = "https://tm.test/discovery/v2";
    private static final String KEY = "secret-key-123";

    private MockRestServiceServer server;
    private TicketmasterSource source;

    @BeforeEach
    void setUp() {
        source = sourceWith(KEY, 200, 5);
    }

    // ---------- the request ----------

    @Test
    void sendsTheFeedsQuery() {
        server.expect(requestTo(startsWith(BASE + "/events.json?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("apikey", KEY))
                .andExpect(queryParam("geoPoint", Geohash.encode(37.7749, -122.4194, 9)))
                .andExpect(queryParam("radius", "25"))
                .andExpect(queryParam("unit", "miles"))
                .andExpect(queryParam("sort", "date,asc"))
                .andExpect(queryParam("size", "200"))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("segmentName", "Music"))
                .andExpect(queryParam("classificationName", "Rock"))
                .andRespond(withSuccess(page(0, 1), MediaType.APPLICATION_JSON));

        source.fetch(feed(LAT, "37.7749", LON, "-122.4194", RADIUS, "25", SEGMENT, "Music", CLASSIFICATION, "Rock"));

        server.verify();
    }

    @Test
    void leavesOutFiltersTheFeedDoesNotSet() {
        server.expect(requestTo(allOf(not(containsString("segmentName")), not(containsString("classificationName")))))
                .andRespond(withSuccess(page(0, 1), MediaType.APPLICATION_JSON));

        source.fetch(nearSanFrancisco());

        server.verify();
    }

    @Test
    void anAmpersandInsideAValueStaysInsideIt() {
        // "Arts & Theatre" is one of Ticketmaster's own segment names. Sent bare, the &
        // would end the parameter and the search would silently drop the filter.
        server.expect(requestTo(containsString("segmentName=Arts%20%26%20Theatre")))
                .andRespond(withSuccess(page(0, 1), MediaType.APPLICATION_JSON));

        source.fetch(feed(LAT, "37.7749", LON, "-122.4194", RADIUS, "25", SEGMENT, "Arts & Theatre"));

        server.verify();
    }

    // ---------- normalising ----------

    @Test
    void normalisesEachEventAndSkipsOnesItCannotIdentify() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withSuccess(fixture("/ticketmaster/events-page.json"), MediaType.APPLICATION_JSON));

        List<Event> events = source.fetch(nearSanFrancisco());

        assertEquals(List.of("vvG1zZ9onsale", "vvG1zZ9canceled", "vvG1zZ9tba"),
                events.stream().map(Event::externalId).toList(),
                "the event with no id is skipped, and only that one");

        Event onSale = events.get(0);
        assertEquals(SourceType.TICKETMASTER, onSale.source());
        assertEquals("Band Live", onSale.title());
        assertTrue(OffsetDateTime.parse("2027-01-01T04:00:00Z").isEqual(onSale.startsAt()));
        assertTrue(OffsetDateTime.parse("2026-11-01T17:00:00Z").isEqual(onSale.onSaleAt()));
        assertEquals("The Fillmore, San Francisco", onSale.venue());
        assertEquals(new BigDecimal("49.50"), onSale.price(), "the cheapest of every listed range");
        assertEquals("https://www.ticketmaster.com/event/vvG1zZ9onsale", onSale.url());
        assertEquals(EventStatus.SCHEDULED, onSale.status());

        assertEquals(EventStatus.CANCELED, events.get(1).status());

        Event tba = events.get(2);
        assertNull(tba.startsAt(), "a TBA date is unknown, not guessed");
        assertNull(tba.onSaleAt(), "a TBD on-sale date is unknown, not guessed");
        assertNull(tba.venue());
        assertNull(tba.price(), "no price ranges yet");
        assertEquals(EventStatus.SCHEDULED, tba.status(), "offsale is not a cancellation");
    }

    @Test
    void mapsStatusCodes() {
        assertEquals(EventStatus.CANCELED, TicketmasterSource.status("canceled"));
        assertEquals(EventStatus.CANCELED, TicketmasterSource.status("Cancelled"));
        assertEquals(EventStatus.POSTPONED, TicketmasterSource.status("postponed"));
        assertEquals(EventStatus.RESCHEDULED, TicketmasterSource.status("rescheduled"));
        assertEquals(EventStatus.SCHEDULED, TicketmasterSource.status("onsale"));
        assertEquals(EventStatus.SCHEDULED, TicketmasterSource.status("offsale"));
        assertEquals(EventStatus.SCHEDULED, TicketmasterSource.status(""));
        assertEquals(EventStatus.SCHEDULED, TicketmasterSource.status("somethingNew"));
    }

    @Test
    void noResultsIsAnEmptyList() {
        // With nothing found, Ticketmaster leaves out _embedded altogether
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withSuccess(page(0, 0), MediaType.APPLICATION_JSON));

        assertTrue(source.fetch(nearSanFrancisco()).isEmpty());
    }

    // ---------- paging ----------

    @Test
    void followsPagesToTheLast() {
        server.expect(queryParam("page", "0"))
                .andRespond(withSuccess(page(0, 2, "a1", "a2"), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page", "1"))
                .andRespond(withSuccess(page(1, 2, "b1"), MediaType.APPLICATION_JSON));

        List<Event> events = source.fetch(nearSanFrancisco());

        assertEquals(List.of("a1", "a2", "b1"), events.stream().map(Event::externalId).toList());
        server.verify();
    }

    @Test
    void neverAsksForAPageTheApiWouldRefuse() {
        // maxPages allows 10, but 10 x 200 is past the API's 1000-result limit
        source = sourceWith(KEY, 200, 10);
        server.expect(times(5), requestTo(startsWith(BASE)))
                .andRespond(withSuccess(page(0, 50, "x"), MediaType.APPLICATION_JSON));

        source.fetch(nearSanFrancisco());

        server.verify();
    }

    // ---------- failures ----------

    @Test
    void anErrorResponseCarriesItsStatusAndRetryAfter() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "30")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"fault\":{\"faultstring\":\"Rate limit quota violation\"}}"));

        SourceException ex = assertThrows(SourceException.class, () -> source.fetch(nearSanFrancisco()));

        assertEquals(429, ex.getStatus());
        assertEquals(Duration.ofSeconds(30), ex.getRetryAfter());
        assertTrue(ex.getMessage().contains("Rate limit quota violation"));
    }

    @Test
    void theApiKeyNeverReachesAnErrorWhenTheConnectionFails() {
        // Spring 6.2 leaves the query, and so the key, out of its own I/O error message.
        // This pins the outcome rather than that detail, in case either ever changes.
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withException(new IOException("Connection reset")));

        SourceException ex = assertThrows(SourceException.class, () -> source.fetch(nearSanFrancisco()));

        assertKeyAbsentFrom(ex);
        assertTrue(ex.getMessage().contains("Connection reset"), "but the actual problem is kept");
    }

    @Test
    void theApiKeyNeverReachesAnErrorWhenTheResponseEchoesIt() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"fault\":{\"faultstring\":\"Invalid ApiKey " + KEY + "\"}}"));

        SourceException ex = assertThrows(SourceException.class, () -> source.fetch(nearSanFrancisco()));

        assertEquals(401, ex.getStatus());
        assertKeyAbsentFrom(ex);
    }

    @Test
    void withoutAKeyNothingIsSent() {
        source = sourceWith("  ", 200, 5);

        SourceException ex = assertThrows(SourceException.class, () -> source.fetch(nearSanFrancisco()));

        assertTrue(ex.getMessage().contains("TICKETMASTER_API_KEY"));
        server.verify(); // no expectations, so any request would already have failed
    }

    @Test
    void aFeedWithBadParamsIsRejectedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class, () -> source.fetch(feed(LON, "-122.4", RADIUS, "25")));
        assertThrows(IllegalArgumentException.class,
                () -> source.fetch(feed(LAT, "200", LON, "-122.4", RADIUS, "25")));
        assertThrows(IllegalArgumentException.class,
                () -> source.fetch(feed(LAT, "37.7", LON, "-122.4", RADIUS, "far")));
        server.verify();
    }

    // ---------- helpers ----------

    private TicketmasterSource sourceWith(String key, int pageSize, int maxPages) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TicketmasterSource(builder, BASE, key, pageSize, maxPages);
    }

    private static Feed nearSanFrancisco() {
        return feed(LAT, "37.7749", LON, "-122.4194", RADIUS, "25");
    }

    private static Feed feed(String... keysAndValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        Feed f = new Feed();
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(params);
        return f;
    }

    /** A search response holding minimal events with the given ids. */
    private static String page(int number, int totalPages, String... ids) {
        String events = Arrays.stream(ids)
                .map(id -> "{\"id\":\"" + id + "\",\"name\":\"Show " + id + "\"}")
                .collect(Collectors.joining(","));
        String embedded = ids.length == 0 ? "" : "\"_embedded\":{\"events\":[" + events + "]},";
        return "{" + embedded + "\"page\":{\"size\":200,\"totalElements\":" + totalPages * 200
                + ",\"totalPages\":" + totalPages + ",\"number\":" + number + "}}";
    }

    private static String fixture(String path) {
        try (InputStream in = TicketmasterSourceTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertKeyAbsentFrom(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage());
            assertFalse(message.contains(KEY), "API key leaked into: " + message);
        }
    }
}
