package com.david.notification_hub.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The slash commands, in the shape Discord's "bulk overwrite application commands" call
 * takes. {@link CommandRegistrar} sends this list; {@link CommandHandler} answers it.
 *
 * <pre>
 *   /subscribe concerts zip:94110 radius:25 [genre:Rock]
 *   /watch game:Hades under:20
 *   /list
 *   /unsubscribe id:12
 * </pre>
 *
 * Discord shows options by name, so {@code /watch "Hades" under 20} reads as
 * {@code /watch game:Hades under:20}. Required options must come before optional ones.
 */
final class DiscordCommands {

    // Discord's application command option types
    static final int SUB_COMMAND = 1;
    static final int STRING = 3;
    static final int INTEGER = 4;
    static final int NUMBER = 10;

    static final int MAX_RADIUS = 100;
    static final int MAX_PRICE = 1000;
    static final int MAX_GAME_TITLE = 100;

    private DiscordCommands() {}

    static List<Map<String, Object>> definitions() {
        Map<String, Object> zip = option(STRING, "zip", "5-digit US zip code", true);
        zip.put("min_length", 5);
        zip.put("max_length", 5);

        Map<String, Object> radius = option(INTEGER, "radius", "Miles around the zip code", true);
        radius.put("min_value", 1);
        radius.put("max_value", MAX_RADIUS);

        Map<String, Object> genre = option(STRING, "genre", "Only this genre (leave out for every concert)", false);
        List<Map<String, Object>> choices = new ArrayList<>();
        for (String g : CommandHandler.GENRES) choices.add(Map.of("name", g, "value", g));
        genre.put("choices", choices);

        Map<String, Object> concerts = option(SUB_COMMAND, "concerts", "Concerts near a US zip code", false);
        concerts.remove("required");
        concerts.put("options", List.of(zip, radius, genre));

        Map<String, Object> game = option(STRING, "game", "Game title, or part of one", true);
        game.put("max_length", MAX_GAME_TITLE);

        Map<String, Object> under = option(NUMBER, "under", "Most you'd pay, in US dollars", true);
        under.put("min_value", 0.01);
        under.put("max_value", MAX_PRICE);

        Map<String, Object> id = option(INTEGER, "id", "The number /list shows", true);
        id.put("min_value", 1);

        return List.of(
                command("subscribe", "Get alerts about new events near you", List.of(concerts)),
                command("watch", "Get alerts when a game is on sale under a price", List.of(game, under)),
                command("list", "Show your subscriptions", List.of()),
                command("unsubscribe", "Stop one of your subscriptions", List.of(id)));
    }

    private static Map<String, Object> command(String name, String description, List<Map<String, Object>> options) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("description", description);
        if (!options.isEmpty()) c.put("options", options);
        return c;
    }

    private static Map<String, Object> option(int type, String name, String description, boolean required) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", type);
        o.put("name", name);
        o.put("description", description);
        o.put("required", required);
        return o;
    }
}
