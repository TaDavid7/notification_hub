package com.david.notification_hub.source.ticketmaster;

import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.FeedParams;
import com.david.notification_hub.source.Source;
import com.david.notification_hub.source.SourceException;
import com.david.notification_hub.source.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Events near a point, from the Ticketmaster Discovery API's event search.
 *
 * Feed params: {@link #LAT}, {@link #LON} and {@link #RADIUS} (miles) are required;
 * {@link #SEGMENT} ("Music", "Sports", ...) and {@link #CLASSIFICATION} (a genre such as
 * "Rock", or any other classification name) narrow it down.
 *
 * Without TICKETMASTER_API_KEY the bean still exists, but every fetch fails with a
 * message saying so, the way an expired key would.
 */
@Component
public class TicketmasterSource implements Source {

    private static final Logger log = LoggerFactory.getLogger(TicketmasterSource.class);

    public static final String LAT = "lat";
    public static final String LON = "lon";
    public static final String RADIUS = "radius";
    public static final String SEGMENT = "segment";
    public static final String CLASSIFICATION = "classification";

    // The API refuses to page past this many results, whatever the page size
    static final int DEEP_PAGING_LIMIT = 1000;

    // About 5m cells - far finer than any radius, so encoding the point costs nothing
    private static final int GEOHASH_PRECISION = 9;

    private final RestClient http;
    private final String apiKey;
    private final int pageSize;
    private final int maxPages;

    public TicketmasterSource(
            RestClient.Builder builder,
            @Value("${ticketmaster.baseUrl:https://app.ticketmaster.com/discovery/v2}") String baseUrl,
            @Value("${ticketmaster.apiKey:}") String apiKey,
            @Value("${ticketmaster.pageSize:200}") int pageSize,
            @Value("${ticketmaster.maxPages:5}") int maxPages) {
        this.http = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.pageSize = pageSize;
        this.maxPages = maxPages;

        if (this.apiKey.isEmpty()) {
            log.info("Ticketmaster disabled (set TICKETMASTER_API_KEY to enable).");
        }
    }

    @Override
    public SourceType type() {
        return SourceType.TICKETMASTER;
    }

    @Override
    public List<Event> fetch(Feed feed) {
        if (apiKey.isEmpty()) {
            throw new SourceException(type(), "TICKETMASTER_API_KEY is not set");
        }

        Map<String, String> params = feed.getParams();
        double lat = FeedParams.number(params, LAT, -90, 90).doubleValue();
        double lon = FeedParams.number(params, LON, -180, 180).doubleValue();
        int radius = FeedParams.number(params, RADIUS, 1, 500).intValue();
        String segment = FeedParams.optionalText(params, SEGMENT);
        String classification = FeedParams.optionalText(params, CLASSIFICATION);

        // Values go in as URI variables rather than inline so they are fully encoded:
        // "Arts & Theatre" is a real segment name, and a bare & would split the parameter
        Map<String, Object> vars = new HashMap<>();
        vars.put("apikey", apiKey);
        vars.put("geoPoint", Geohash.encode(lat, lon, GEOHASH_PRECISION));
        vars.put("radius", radius);
        vars.put("size", pageSize);
        vars.put("segment", segment);
        vars.put("classification", classification);

        // Never more pages than the API will serve, so the last request can't be refused
        int pagesAllowed = Math.min(maxPages, DEEP_PAGING_LIMIT / pageSize);

        List<Event> out = new ArrayList<>();
        for (int page = 0; page < pagesAllowed; page++) {
            vars.put("page", page);
            JsonNode body = get(b -> searchUri(b, vars, segment != null, classification != null));

            for (JsonNode e : body.path("_embedded").path("events")) {
                toEvent(e).ifPresent(out::add);
            }

            int totalPages = body.path("page").path("totalPages").asInt(0);
            if (page + 1 >= totalPages) break;
            if (page + 1 == pagesAllowed) {
                log.debug("Ticketmaster results truncated at {} pages of {} (of {})", pagesAllowed, pageSize, totalPages);
            }
        }
        return out;
    }

    private static URI searchUri(UriBuilder b, Map<String, Object> vars, boolean bySegment, boolean byClassification) {
        b.path("/events.json")
                .queryParam("apikey", "{apikey}")
                .queryParam("geoPoint", "{geoPoint}")
                .queryParam("radius", "{radius}")
                .queryParam("unit", "miles")
                // Soonest first, so a truncated result drops the far future, not next week
                .queryParam("sort", "date,asc")
                .queryParam("size", "{size}")
                .queryParam("page", "{page}");
        if (bySegment) b.queryParam("segmentName", "{segment}");
        if (byClassification) b.queryParam("classificationName", "{classification}");
        return b.build(vars);
    }

    private JsonNode get(Function<UriBuilder, URI> uri) {
        try {
            JsonNode body = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (body == null) throw new SourceException(type(), "empty response");
            return body;
        } catch (RestClientException e) {
            throw SourceException.from(type(), e, apiKey);
        }
    }

    private Optional<Event> toEvent(JsonNode e) {
        JsonNode dates = e.path("dates");
        try {
            return Optional.of(new Event(
                    SourceType.TICKETMASTER,
                    text(e, "id"),
                    text(e, "name"),
                    // Absent when the date or time is TBA; a guessed time would make the
                    // starting-soon reminder fire at the wrong moment
                    dateTime(dates.path("start").path("dateTime")),
                    onSaleAt(e.path("sales").path("public")),
                    venue(e.path("_embedded").path("venues").path(0)),
                    lowestPrice(e.path("priceRanges")),
                    text(e, "url"),
                    status(dates.path("status").path("code").asText(""))));
        } catch (IllegalArgumentException ex) {
            // One malformed event must not cost the rest of the page
            log.warn("Skipping a Ticketmaster event that could not be read: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static OffsetDateTime onSaleAt(JsonNode publicSale) {
        if (publicSale.path("startTBD").asBoolean(false) || publicSale.path("startTBA").asBoolean(false)) {
            return null;
        }
        return dateTime(publicSale.path("startDateTime"));
    }

    // "The Fillmore, San Francisco" - venue names alone repeat from city to city
    private static String venue(JsonNode venue) {
        String name = text(venue, "name");
        if (name == null) return null;
        String city = text(venue.path("city"), "name");
        return city == null ? name : name + ", " + city;
    }

    // The cheapest ticket across every price range listed, e.g. with and without fees
    private static BigDecimal lowestPrice(JsonNode ranges) {
        BigDecimal lowest = null;
        for (JsonNode range : ranges) {
            JsonNode min = range.path("min");
            if (!min.isNumber()) continue;
            BigDecimal value = min.decimalValue();
            if (lowest == null || value.compareTo(lowest) < 0) lowest = value;
        }
        return lowest;
    }

    static EventStatus status(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "canceled", "cancelled" -> EventStatus.CANCELED;
            case "postponed" -> EventStatus.POSTPONED;
            case "rescheduled" -> EventStatus.RESCHEDULED;
            // onsale, offsale, and anything new: still going ahead as far as we know
            default -> EventStatus.SCHEDULED;
        };
    }

    private static OffsetDateTime dateTime(JsonNode node) {
        String value = node.isTextual() ? node.asText() : null;
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
