package com.david.notification_hub.source.cheapshark;

import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.SourceException;
import com.david.notification_hub.source.SourceType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.david.notification_hub.source.cheapshark.CheapSharkSource.MAX_PRICE;
import static com.david.notification_hub.source.cheapshark.CheapSharkSource.TITLE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CheapShark is played by MockRestServiceServer; nothing here touches the network. */
class CheapSharkSourceTest {

    private static final String BASE = "https://cs.test/api/1.0";

    private MockRestServiceServer server;
    private CheapSharkSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new CheapSharkSource(builder, BASE);
    }

    // ---------- the request ----------

    @Test
    void asksForTheTitleUnderTheCapCheapestFirst() {
        server.expect(requestTo(startsWith(BASE + "/deals?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("title", "hades"))
                .andExpect(queryParam("upperPrice", "20"))
                .andExpect(queryParam("sortBy", "Price"))
                .andExpect(queryParam("pageSize", "60"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        source.fetch(watch("hades", "20"));

        server.verify();
    }

    @Test
    void anAmpersandInATitleStaysInTheTitle() {
        server.expect(requestTo(containsString("title=Ratchet%20%26%20Clank")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        source.fetch(watch("Ratchet & Clank", "20"));

        server.verify();
    }

    // ---------- normalising ----------

    @Test
    void oneEventPerGameAtItsCheapestDeal() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withSuccess(fixture("/cheapshark/deals.json"), MediaType.APPLICATION_JSON));

        List<Event> events = source.fetch(watch("hades", "20"));

        // Five deals: two stores each for two games, and one with no game id to skip
        assertEquals(List.of("200", "300"), events.stream().map(Event::externalId).toList());

        Event hades = events.get(0);
        assertEquals(SourceType.CHEAPSHARK, hades.source());
        assertEquals("Hades", hades.title());
        assertEquals(new BigDecimal("9.99"), hades.price(), "GOG's 9.99, not Steam's 12.49");
        assertEquals("https://www.cheapshark.com/redirect?dealID=gogHades%3D", hades.url(),
                "the dealID arrives encoded and must not be encoded twice");
        assertNull(hades.startsAt());
        assertNull(hades.onSaleAt());
        assertNull(hades.venue());
        assertEquals(EventStatus.SCHEDULED, hades.status());

        // Hades II is 19.99 at two stores; the lower store id wins
        assertEquals("https://www.cheapshark.com/redirect?dealID=steamHades2%3D", events.get(1).url());
    }

    @Test
    void aTieIsSettledTheSameWayWhateverTheOrder() {
        String epicFirst = "[" + deal("300", "25", "epic", "19.99") + "," + deal("300", "1", "steam", "19.99") + "]";
        String steamFirst = "[" + deal("300", "1", "steam", "19.99") + "," + deal("300", "25", "epic", "19.99") + "]";
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(epicFirst, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(steamFirst, MediaType.APPLICATION_JSON));

        String first = source.fetch(watch("hades", "20")).get(0).url();
        String second = source.fetch(watch("hades", "20")).get(0).url();

        // Otherwise the event's link would flip between polls for no reason
        assertEquals(first, second);
        assertTrue(first.endsWith("steam"));
    }

    @Test
    void dealsOverTheCapAreDroppedEvenWhenCheapSharkReturnsThem() {
        // CheapShark treats upperPrice 50 and above as no limit at all
        String deals = "[" + deal("1", "1", "under", "59.99") + "," + deal("2", "1", "over", "70.00") + "]";
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(deals, MediaType.APPLICATION_JSON));

        List<Event> events = source.fetch(watch("elden", "60"));

        assertEquals(List.of("1"), events.stream().map(Event::externalId).toList());
    }

    @Test
    void noDealsIsAnEmptyList() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertTrue(source.fetch(watch("nothing matches this", "5")).isEmpty());
    }

    // ---------- failures ----------

    @Test
    void anErrorResponseCarriesItsStatusAndRetryAfter() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60"));

        SourceException ex = assertThrows(SourceException.class, () -> source.fetch(watch("hades", "20")));

        assertEquals(429, ex.getStatus());
        assertEquals(Duration.ofSeconds(60), ex.getRetryAfter());
    }

    @Test
    void aResponseThatIsNotAListIsAnError() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withSuccess("{\"error\":\"something\"}", MediaType.APPLICATION_JSON));

        assertThrows(SourceException.class, () -> source.fetch(watch("hades", "20")));
    }

    @Test
    void aFeedWithBadParamsIsRejectedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class, () -> source.fetch(feed(MAX_PRICE, "20")));
        assertThrows(IllegalArgumentException.class, () -> source.fetch(watch("hades", "cheap")));
        assertThrows(IllegalArgumentException.class, () -> source.fetch(watch("hades", "-1")));
        server.verify();
    }

    // ---------- helpers ----------

    private static Feed watch(String title, String maxPrice) {
        return feed(TITLE, title, MAX_PRICE, maxPrice);
    }

    private static Feed feed(String... keysAndValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        Feed f = new Feed();
        f.setSourceType(SourceType.CHEAPSHARK);
        f.setParams(params);
        return f;
    }

    private static String deal(String gameId, String storeId, String dealId, String price) {
        return "{\"title\":\"Game " + gameId + "\",\"gameID\":\"" + gameId + "\",\"storeID\":\"" + storeId
                + "\",\"dealID\":\"" + dealId + "\",\"salePrice\":\"" + price + "\"}";
    }

    private static String fixture(String path) {
        try (InputStream in = CheapSharkSourceTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
