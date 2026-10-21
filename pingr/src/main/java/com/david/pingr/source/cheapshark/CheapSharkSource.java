package com.david.pingr.source.cheapshark;

import com.david.pingr.feed.Feed;
import com.david.pingr.source.Event;
import com.david.pingr.source.EventStatus;
import com.david.pingr.source.FeedParams;
import com.david.pingr.source.Source;
import com.david.pingr.source.SourceException;
import com.david.pingr.source.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game deals under a price, from CheapShark's deals list. No API key.
 *
 * Feed params: {@link #TITLE} and {@link #MAX_PRICE} (USD), both required.
 *
 * One event per game, not per deal: CheapShark lists a game once for every store
 * selling it, and the subscriber asked about the game. The event is that game's
 * cheapest deal, so a price drop anywhere is an update to it rather than a new item.
 */
@Component
public class CheapSharkSource implements Source {

    private static final Logger log = LoggerFactory.getLogger(CheapSharkSource.class);

    public static final String TITLE = "title";
    public static final String MAX_PRICE = "maxPrice";

    // The most CheapShark returns per page
    private static final int PAGE_SIZE = 60;

    // dealIDs arrive already URL-encoded, so they are appended as they are
    private static final String DEAL_LINK = "https://www.cheapshark.com/redirect?dealID=";

    // Cheapest first; equal prices are settled by store, then deal, so the same deals
    // always pick the same winner whatever order they arrive in
    private static final Comparator<JsonNode> CHEAPEST = Comparator
            .comparing((JsonNode d) -> price(d))
            .thenComparing(d -> d.path("storeID").asInt(Integer.MAX_VALUE))
            .thenComparing(d -> d.path("dealID").asText(""));

    private final RestClient http;

    public CheapSharkSource(
            RestClient.Builder builder,
            @Value("${cheapshark.baseUrl:https://www.cheapshark.com/api/1.0}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public SourceType type() {
        return SourceType.CHEAPSHARK;
    }

    @Override
    public List<Event> fetch(Feed feed) {
        Map<String, String> params = feed.getParams();
        String title = FeedParams.text(params, TITLE);
        BigDecimal maxPrice = FeedParams.number(params, MAX_PRICE, 0, 100_000);

        JsonNode deals;
        try {
            deals = http.get()
                    .uri(b -> b.path("/deals")
                            .queryParam("title", "{title}")
                            .queryParam("upperPrice", "{upperPrice}")
                            // Cheapest first, so one page always holds the best deal of
                            // every game it lists
                            .queryParam("sortBy", "Price")
                            .queryParam("pageSize", PAGE_SIZE)
                            .build(Map.of("title", title, "upperPrice", maxPrice.toPlainString())))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw SourceException.from(type(), e);
        }
        if (deals == null || !deals.isArray()) {
            throw new SourceException(type(), "expected a JSON array of deals");
        }

        Map<String, JsonNode> bestPerGame = new LinkedHashMap<>();
        for (JsonNode deal : deals) {
            String gameId = deal.path("gameID").asText("");
            BigDecimal price = price(deal);
            // upperPrice of 50 or more means "no limit" to CheapShark, so the cap is
            // enforced here as well
            if (gameId.isEmpty() || price == null || price.compareTo(maxPrice) > 0) continue;

            bestPerGame.merge(gameId, deal, (a, b) -> CHEAPEST.compare(a, b) <= 0 ? a : b);
        }

        List<Event> out = new ArrayList<>(bestPerGame.size());
        for (Map.Entry<String, JsonNode> entry : bestPerGame.entrySet()) {
            JsonNode deal = entry.getValue();
            String dealId = deal.path("dealID").asText("");
            try {
                out.add(new Event(
                        SourceType.CHEAPSHARK,
                        entry.getKey(),
                        deal.path("title").asText(null),
                        null, null, null,
                        price(deal),
                        dealId.isEmpty() ? null : DEAL_LINK + dealId,
                        EventStatus.SCHEDULED));
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping a CheapShark deal that could not be read: {}", ex.getMessage());
            }
        }
        return out;
    }

    // Prices arrive as strings ("9.99"); null when missing or unreadable
    private static BigDecimal price(JsonNode deal) {
        String value = deal.path("salePrice").asText("");
        try {
            return value.isBlank() ? null : new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
