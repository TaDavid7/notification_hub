package com.david.notification_hub.discord;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The command definitions, and registering them with a Discord played by MockRestServiceServer. */
class DiscordCommandsTest {

    private static final String API = "https://discord.test/api/v10";

    // ---------- definitions ----------

    @Test
    void definesTheFourCommands() {
        assertEquals(List.of("subscribe", "watch", "list", "unsubscribe"),
                DiscordCommands.definitions().stream().map(c -> c.get("name")).toList());
    }

    @Test
    void concertsTakeAZipARadiusAndOptionallyAGenreFromTheList() {
        Map<String, Object> concerts = only(options(command("subscribe")));
        assertEquals("concerts", concerts.get("name"));
        assertEquals(DiscordCommands.SUB_COMMAND, concerts.get("type"));

        List<Map<String, Object>> opts = options(concerts);
        assertEquals(List.of("zip", "radius", "genre"), opts.stream().map(o -> o.get("name")).toList());
        assertEquals(List.of(true, true, false), opts.stream().map(o -> o.get("required")).toList());

        List<?> genreValues = ((List<?>) opts.get(2).get("choices")).stream()
                .map(c -> ((Map<?, ?>) c).get("value")).toList();
        assertEquals(CommandHandler.GENRES, genreValues);
    }

    @Test
    void watchTakesAGameAndAPrice() {
        List<Map<String, Object>> opts = options(command("watch"));

        assertEquals(List.of("game", "under"), opts.stream().map(o -> o.get("name")).toList());
        assertEquals(DiscordCommands.NUMBER, opts.get(1).get("type"), "a price needs decimals");
    }

    @Test
    void everyCommandKeepsToDiscordsRules() {
        // Discord rejects the whole set if any one breaks these, so check them all here
        for (Map<String, Object> c : DiscordCommands.definitions()) checkRules(c);
    }

    // ---------- registration ----------

    @Test
    void registersToOneServerWhenAGuildIsSet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API + "/applications/app-1/guilds/guild-1/commands"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bot bot-token"))
                .andExpect(jsonPath("$[0].name").value("subscribe"))
                .andExpect(jsonPath("$.length()").value(4))
                .andRespond(withSuccess());

        assertTrue(new CommandRegistrar(builder, API, "app-1", "bot-token", "guild-1").register());
        server.verify();
    }

    @Test
    void registersGloballyWithoutAGuild() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API + "/applications/app-1/commands"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        assertTrue(new CommandRegistrar(builder, API, "app-1", "bot-token", "").register());
        server.verify();
    }

    @Test
    void withoutCredentialsNothingIsSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertFalse(new CommandRegistrar(builder, API, "", "", "").register());
        assertFalse(new CommandRegistrar(builder, API, "app-1", "", "").register());
        server.verify();
    }

    @Test
    void aRefusedRegistrationIsLoggedNotThrown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API + "/applications/app-1/commands"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"401: Unauthorized\"}"));

        assertFalse(new CommandRegistrar(builder, API, "app-1", "wrong-token", "").register());
    }

    // ---------- helpers ----------

    private static Map<String, Object> command(String name) {
        return DiscordCommands.definitions().stream()
                .filter(c -> name.equals(c.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> options(Map<String, Object> commandOrOption) {
        return (List<Map<String, Object>>) commandOrOption.getOrDefault("options", List.of());
    }

    private static Map<String, Object> only(List<Map<String, Object>> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    private static void checkRules(Map<String, Object> node) {
        String name = (String) node.get("name");
        String description = (String) node.get("description");
        assertTrue(name.matches("[-_a-z0-9]{1,32}"), "bad name: " + name);
        assertTrue(description.length() >= 1 && description.length() <= 100, "bad description on " + name);

        Object choices = node.get("choices");
        if (choices != null) assertTrue(((List<?>) choices).size() <= 25, "too many choices on " + name);

        boolean optionalSeen = false;
        for (Map<String, Object> o : options(node)) {
            if (Boolean.FALSE.equals(o.get("required"))) optionalSeen = true;
            if (Boolean.TRUE.equals(o.get("required"))) {
                assertFalse(optionalSeen, "required option after an optional one in " + name);
            }
            checkRules(o);
        }
    }
}
