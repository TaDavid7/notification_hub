package com.david.notification_hub.discord;

import com.david.notification_hub.alert.Alerts;
import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.feed.SubscriptionService;
import com.david.notification_hub.geo.ZipCodes;
import com.david.notification_hub.source.SourceType;
import com.david.notification_hub.source.cheapshark.CheapSharkSource;
import com.david.notification_hub.source.ticketmaster.TicketmasterSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Answers the slash commands in {@link DiscordCommands}. Each answer is the text shown
 * back to the person who ran it.
 *
 * Turning a command into feed params is where grouping is won or lost: two people asking
 * the same thing must produce exactly the same params to share one poll. So a zip
 * becomes the gazetteer's coordinates verbatim, a game title is lower-cased with its
 * spaces collapsed, and a price always has two decimals.
 *
 * Discord enforces the option limits in its own UI; they are checked again here because
 * anyone holding a valid signature could send anything.
 */
@Component
public class CommandHandler {

    // Ticketmaster's music genre names, offered as the genre choices
    static final List<String> GENRES = List.of(
            "Alternative", "Blues", "Classical", "Country", "Dance/Electronic", "Folk", "Hip-Hop/Rap",
            "Jazz", "Latin", "Metal", "Pop", "R&B", "Reggae", "Rock", "World");

    private final SubscriptionService subscriptions;
    private final ZipCodes zipCodes;

    public CommandHandler(SubscriptionService subscriptions, ZipCodes zipCodes) {
        this.subscriptions = subscriptions;
        this.zipCodes = zipCodes;
    }

    public String handle(JsonNode interaction) {
        String userId = userId(interaction);
        if (userId == null) return "I couldn't tell who sent that.";

        JsonNode data = interaction.path("data");
        String name = data.path("name").asText("");
        return switch (name) {
            case "subscribe" -> subscribe(userId, data.path("options"));
            case "watch" -> watch(userId, options(data.path("options")));
            case "list" -> list(userId);
            case "unsubscribe" -> unsubscribe(userId, options(data.path("options")));
            default -> "I don't know that command.";
        };
    }

    // ---------- commands ----------

    private String subscribe(String userId, JsonNode options) {
        JsonNode sub = options.path(0);
        if (!"concerts".equals(sub.path("name").asText())) return "I don't know that kind of subscription.";
        Map<String, JsonNode> opts = options(sub.path("options"));

        String zip = text(opts, "zip");
        if (zip == null || !zip.matches("\\d{5}")) return "Zip codes are 5 digits, like 94110.";
        Optional<ZipCodes.Point> point = zipCodes.find(zip);
        if (point.isEmpty()) return "I couldn't find the US zip code " + zip + ".";

        int radius = opts.containsKey("radius") ? opts.get("radius").asInt(0) : 0;
        if (radius < 1 || radius > DiscordCommands.MAX_RADIUS) {
            return "Radius must be between 1 and " + DiscordCommands.MAX_RADIUS + " miles.";
        }

        String genre = text(opts, "genre");
        if (genre != null && !GENRES.contains(genre)) return "Pick a genre from the list.";

        Map<String, String> params = new LinkedHashMap<>();
        params.put(TicketmasterSource.LAT, point.get().lat());
        params.put(TicketmasterSource.LON, point.get().lon());
        params.put(TicketmasterSource.RADIUS, String.valueOf(radius));
        params.put(TicketmasterSource.SEGMENT, "Music");
        if (genre != null) params.put(TicketmasterSource.CLASSIFICATION, genre);
        params.put(TicketmasterSource.ZIP, zip);

        return reply(subscriptions.subscribe(userId, SourceType.TICKETMASTER, params),
                Alerts.describe(SourceType.TICKETMASTER, params),
                "You'll be mentioned when shows are announced, change, or are about to start.");
    }

    private String watch(String userId, Map<String, JsonNode> opts) {
        String raw = text(opts, "game");
        String game = raw == null ? "" : raw.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        if (game.isEmpty()) return "Tell me which game to watch.";
        if (game.length() > DiscordCommands.MAX_GAME_TITLE) return "That title is too long.";

        JsonNode under = opts.get("under");
        BigDecimal price = under == null || !under.isNumber()
                ? null
                : under.decimalValue().setScale(2, RoundingMode.HALF_UP);
        if (price == null || price.signum() <= 0 || price.compareTo(BigDecimal.valueOf(DiscordCommands.MAX_PRICE)) > 0) {
            return "The price has to be more than $0 and at most $" + DiscordCommands.MAX_PRICE + ".";
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put(CheapSharkSource.TITLE, game);
        params.put(CheapSharkSource.MAX_PRICE, price.toPlainString());

        return reply(subscriptions.subscribe(userId, SourceType.CHEAPSHARK, params),
                Alerts.describe(SourceType.CHEAPSHARK, params),
                "You'll be mentioned when it's on sale for that or less.");
    }

    private String list(String userId) {
        List<Feed> feeds = subscriptions.list(userId);
        if (feeds.isEmpty()) return "You have no subscriptions yet. Try /subscribe concerts or /watch.";

        StringBuilder sb = new StringBuilder("Your subscriptions:");
        for (Feed f : feeds) {
            sb.append("\n`#").append(f.getId()).append("` ").append(Alerts.describe(f.getSourceType(), f.getParams()));
        }
        return sb.append("\nStop one with /unsubscribe and its number.").toString();
    }

    private String unsubscribe(String userId, Map<String, JsonNode> opts) {
        long id = opts.containsKey("id") ? opts.get("id").asLong(0) : 0;
        if (id < 1) return "Give the number /list shows.";
        return subscriptions.unsubscribe(userId, id)
                ? "Removed #" + id + "."
                : "You don't have a subscription #" + id + ". /list shows yours.";
    }

    // ---------- helpers ----------

    private static String reply(SubscriptionService.Result result, String description, String whatNext) {
        return switch (result.status()) {
            case CREATED -> "Subscribed (#" + result.feedId() + "): " + description + ". " + whatNext;
            case ALREADY_SUBSCRIBED -> "You're already subscribed to that (#" + result.feedId() + ").";
            case LIMIT_REACHED -> "You're at the limit of subscriptions. Stop one with /unsubscribe first.";
        };
    }

    // In a server the user is under member; in a DM, at the top level. Only digits are
    // accepted, since this id is written into <@...> mentions in every alert.
    private static String userId(JsonNode interaction) {
        String id = interaction.path("member").path("user").path("id").asText("");
        if (id.isEmpty()) id = interaction.path("user").path("id").asText("");
        return id.matches("\\d{1,25}") ? id : null;
    }

    private static Map<String, JsonNode> options(JsonNode options) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode o : options) out.put(o.path("name").asText(), o.path("value"));
        return out;
    }

    private static String text(Map<String, JsonNode> opts, String name) {
        JsonNode v = opts.get(name);
        if (v == null || !v.isTextual() || v.asText().isBlank()) return null;
        return v.asText().strip();
    }
}
