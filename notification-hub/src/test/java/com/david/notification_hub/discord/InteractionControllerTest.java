package com.david.notification_hub.discord;

import com.david.notification_hub.geo.ZipCodes;
import com.david.notification_hub.support.AbstractPostgresTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint end to end: requests signed as Discord signs them, through the real
 * controller and handler, into Postgres.
 *
 * The app is started with this test's own public key, so it plays Discord by signing
 * with the matching private key.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InteractionControllerTest extends AbstractPostgresTest {

    private static final KeyPair DISCORD = Ed25519TestKeys.generate();
    private static final String TS = "1760000000";

    @DynamicPropertySource
    static void discordKey(DynamicPropertyRegistry registry) {
        registry.add("discord.publicKey", () -> Ed25519TestKeys.rawHex(DISCORD.getPublic()));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ZipCodes zipCodes;
    @Autowired ObjectMapper mapper;

    private String alice;

    @BeforeEach
    void newUser() {
        alice = newDiscordId();
    }

    // ---------- the handshake ----------

    @Test
    void aPingIsAnsweredWithPong() throws Exception {
        send("{\"type\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(1));
    }

    @Test
    void anUnsignedOrTamperedRequestIs401() throws Exception {
        // Discord sends one of these on purpose when the endpoint URL is saved
        mvc.perform(post("/discord/interactions").contentType(MediaType.APPLICATION_JSON).content("{\"type\":1}"))
                .andExpect(status().isUnauthorized());

        String signature = Ed25519TestKeys.sign(DISCORD, TS, "{\"type\":1}");
        mvc.perform(post("/discord/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature-Ed25519", signature)
                        .header("X-Signature-Timestamp", TS)
                        .content("{\"type\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repliesAreSeenOnlyByTheCallerAndPingNobody() throws Exception {
        send(command(alice, "list", "[]"))
                .andExpect(jsonPath("$.type").value(4))
                .andExpect(jsonPath("$.data.flags").value(64))
                .andExpect(jsonPath("$.data.allowed_mentions.parse").isEmpty());
    }

    // ---------- /subscribe concerts ----------

    @Test
    void subscribingToConcertsCreatesAFeedForTheCaller() throws Exception {
        send(concerts(alice, "94110", 25, "Rock"))
                .andExpect(jsonPath("$.data.content").value(containsString("concerts within 25 mi of 94110 · Rock")));

        ZipCodes.Point sf = zipCodes.find("94110").orElseThrow();
        assertEquals(List.of(Map.of(
                "lat", sf.lat(), "lon", sf.lon(), "radius", "25",
                "segment", "Music", "classification", "Rock", "zip", "94110")), paramsOf(alice, "TICKETMASTER"));
    }

    @Test
    void withoutAGenreEveryConcertCounts() throws Exception {
        send(concerts(alice, "10001", 10, null));

        assertEquals(List.of("10001"), paramsOf(alice, "TICKETMASTER").stream().map(p -> p.get("zip")).toList());
        assertNull(paramsOf(alice, "TICKETMASTER").get(0).get("classification"));
    }

    @Test
    void subscribingTwiceIsOneSubscription() throws Exception {
        send(concerts(alice, "94110", 25, "Rock"));
        send(concerts(alice, "94110", 25, "Rock"))
                .andExpect(jsonPath("$.data.content").value(containsString("already subscribed")));

        assertEquals(1, paramsOf(alice, "TICKETMASTER").size());
    }

    @Test
    void twoPeopleAskingTheSameThingGetIdenticalParams() throws Exception {
        // This is what lets polling make one call for both of them
        String bob = newDiscordId();
        send(concerts(alice, "94110", 25, "Rock"));
        send(concerts(bob, "94110", 25, "Rock"));

        assertEquals(paramsOf(alice, "TICKETMASTER"), paramsOf(bob, "TICKETMASTER"));
    }

    @Test
    void anUnknownOrMalformedZipCreatesNothing() throws Exception {
        send(concerts(alice, "00000", 25, null))
                .andExpect(jsonPath("$.data.content").value(containsString("couldn't find the US zip code 00000")));
        send(concerts(alice, "9411", 25, null))
                .andExpect(jsonPath("$.data.content").value(containsString("5 digits")));

        assertEquals(List.of(), paramsOf(alice, "TICKETMASTER"));
    }

    @Test
    void outOfRangeValuesAreRefusedEvenThoughDiscordChecksThemToo() throws Exception {
        send(concerts(alice, "94110", 500, null))
                .andExpect(jsonPath("$.data.content").value(containsString("Radius must be between 1 and 100")));
        send(concerts(alice, "94110", 25, "Polka"))
                .andExpect(jsonPath("$.data.content").value(containsString("genre from the list")));

        assertEquals(List.of(), paramsOf(alice, "TICKETMASTER"));
    }

    // ---------- /watch ----------

    @Test
    void watchingAGameNormalisesTitleAndPrice() throws Exception {
        send(watch(alice, "  Hades   II ", "19.999"))
                .andExpect(jsonPath("$.data.content").value(containsString("hades ii under $20.00")));

        assertEquals(List.of(Map.of("title", "hades ii", "maxPrice", "20.00")), paramsOf(alice, "CHEAPSHARK"));
    }

    @Test
    void sameGameDifferentlyTypedIsOneSubscription() throws Exception {
        send(watch(alice, "Hades", "20"));
        send(watch(alice, "HADES", "20.00"))
                .andExpect(jsonPath("$.data.content").value(containsString("already subscribed")));
    }

    @Test
    void aPriceOfNothingIsRefused() throws Exception {
        send(watch(alice, "Hades", "0"))
                .andExpect(jsonPath("$.data.content").value(containsString("more than $0")));

        assertEquals(List.of(), paramsOf(alice, "CHEAPSHARK"));
    }

    @Test
    void theSubscriptionLimitHolds() throws Exception {
        for (int i = 1; i <= 10; i++) send(watch(alice, "game " + i, "20"));

        send(watch(alice, "game 11", "20"))
                .andExpect(jsonPath("$.data.content").value(containsString("limit")));
        assertEquals(10, paramsOf(alice, "CHEAPSHARK").size());

        // ...but asking again for one you already have still answers, rather than refusing
        send(watch(alice, "game 3", "20"))
                .andExpect(jsonPath("$.data.content").value(containsString("already subscribed")));
    }

    // ---------- /list and /unsubscribe ----------

    @Test
    void listShowsOnlyYourOwnSubscriptions() throws Exception {
        String bob = newDiscordId();
        send(concerts(alice, "94110", 25, "Rock"));
        send(watch(alice, "Hades", "20"));
        send(watch(bob, "Celeste", "5"));

        send(command(alice, "list", "[]"))
                .andExpect(jsonPath("$.data.content").value(containsString("concerts within 25 mi of 94110 · Rock")))
                .andExpect(jsonPath("$.data.content").value(containsString("hades under $20.00")))
                .andExpect(jsonPath("$.data.content").value(not(containsString("celeste"))));
    }

    @Test
    void listWithNothingSaysHowToStart() throws Exception {
        send(command(alice, "list", "[]"))
                .andExpect(jsonPath("$.data.content").value(containsString("no subscriptions yet")));
    }

    @Test
    void unsubscribeRemovesYourOwn() throws Exception {
        send(watch(alice, "Hades", "20"));
        long feedId = feedIds(alice).get(0);

        send(unsubscribe(alice, feedId))
                .andExpect(jsonPath("$.data.content").value("Removed #" + feedId + "."));
        assertEquals(List.of(), feedIds(alice));
    }

    @Test
    void youCannotUnsubscribeSomeoneElse() throws Exception {
        String bob = newDiscordId();
        send(watch(bob, "Celeste", "5"));
        long bobsFeed = feedIds(bob).get(0);

        send(unsubscribe(alice, bobsFeed))
                .andExpect(jsonPath("$.data.content").value(containsString("don't have a subscription #" + bobsFeed)));
        assertEquals(List.of(bobsFeed), feedIds(bob));
    }

    // ---------- the rest ----------

    @Test
    void commandsWorkInADirectMessageToo() throws Exception {
        // Outside a server Discord puts the user at the top level instead of under member
        String dm = "{\"type\":2,\"user\":{\"id\":\"" + alice + "\"},"
                + "\"data\":{\"name\":\"watch\",\"options\":["
                + "{\"name\":\"game\",\"type\":3,\"value\":\"Hades\"},{\"name\":\"under\",\"type\":10,\"value\":20}]}}";

        send(dm).andExpect(jsonPath("$.data.content").value(containsString("Subscribed")));
        assertEquals(1, feedIds(alice).size());
    }

    @Test
    void anUnknownCommandGetsAPoliteAnswer() throws Exception {
        send(command(alice, "dance", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("I don't know that command."));
    }

    @Test
    void aUserIdThatIsNotASnowflakeIsRefused() throws Exception {
        // It is written into <@...> mentions, so nothing else may get through
        send(command("<@&1>", "list", "[]"))
                .andExpect(jsonPath("$.data.content").value("I couldn't tell who sent that."));
    }

    // ---------- helpers ----------

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/discord/interactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature-Ed25519", Ed25519TestKeys.sign(DISCORD, TS, body))
                .header("X-Signature-Timestamp", TS)
                .content(body));
    }

    private static String command(String userId, String name, String optionsJson) {
        return "{\"type\":2,\"id\":\"1\",\"application_id\":\"app\",\"token\":\"tok\","
                + "\"guild_id\":\"g\",\"channel_id\":\"c\","
                + "\"member\":{\"user\":{\"id\":\"" + userId + "\",\"username\":\"tester\"}},"
                + "\"data\":{\"id\":\"cmd\",\"name\":\"" + name + "\",\"type\":1,\"options\":" + optionsJson + "}}";
    }

    private static String concerts(String userId, String zip, int radius, String genre) {
        String options = "{\"name\":\"zip\",\"type\":3,\"value\":\"" + zip + "\"},"
                + "{\"name\":\"radius\",\"type\":4,\"value\":" + radius + "}"
                + (genre == null ? "" : ",{\"name\":\"genre\",\"type\":3,\"value\":\"" + genre + "\"}");
        return command(userId, "subscribe", "[{\"type\":1,\"name\":\"concerts\",\"options\":[" + options + "]}]");
    }

    private static String watch(String userId, String game, String under) {
        return command(userId, "watch", "[{\"name\":\"game\",\"type\":3,\"value\":\"" + game + "\"},"
                + "{\"name\":\"under\",\"type\":10,\"value\":" + under + "}]");
    }

    private static String unsubscribe(String userId, long feedId) {
        return command(userId, "unsubscribe", "[{\"name\":\"id\",\"type\":4,\"value\":" + feedId + "}]");
    }

    // Masked rather than Math.abs, which leaves Long.MIN_VALUE negative
    private static String newDiscordId() {
        return String.valueOf(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
    }

    private List<Map<String, String>> paramsOf(String discordUserId, String sourceType) {
        return jdbc.queryForList("""
                SELECT f.params::text FROM feeds f JOIN users u ON u.id = f.user_id
                 WHERE u.external_id = ? AND f.source_type = ? ORDER BY f.id
                """, String.class, discordUserId, sourceType).stream().map(this::parse).toList();
    }

    private List<Long> feedIds(String discordUserId) {
        return jdbc.queryForList("""
                SELECT f.id FROM feeds f JOIN users u ON u.id = f.user_id
                 WHERE u.external_id = ? ORDER BY f.id
                """, Long.class, discordUserId);
    }

    private Map<String, String> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
