package com.david.pingr.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Discord's interactions endpoint: Discord POSTs every slash command here, and the
 * response body is the reply. Set it as the application's Interactions Endpoint URL,
 * e.g. https://your-host/discord/interactions.
 *
 * The body is taken as raw bytes because the signature covers them exactly; parsing
 * first and re-serialising would not reproduce them.
 *
 * Discord waits 3 seconds for an answer, which is why nothing here calls out to anything
 * slower than the database.
 */
@RestController
public class InteractionController {

    private static final Logger log = LoggerFactory.getLogger(InteractionController.class);

    // Interaction types in, response types out
    private static final int PING = 1;
    private static final int APPLICATION_COMMAND = 2;
    private static final int PONG = 1;
    private static final int CHANNEL_MESSAGE_WITH_SOURCE = 4;

    // Message flag: only the person who ran the command sees the reply
    private static final int EPHEMERAL = 1 << 6;

    private final DiscordSignatureVerifier verifier;
    private final CommandHandler handler;
    private final ObjectMapper mapper;

    public InteractionController(DiscordSignatureVerifier verifier, CommandHandler handler, ObjectMapper mapper) {
        this.verifier = verifier;
        this.handler = handler;
        this.mapper = mapper;
    }

    @PostMapping(path = "/discord/interactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> interact(
            @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
            @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp,
            @RequestBody(required = false) byte[] body) {

        if (!verifier.verify(signature, timestamp, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode interaction;
        try {
            interaction = mapper.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }

        return switch (interaction.path("type").asInt()) {
            case PING -> ResponseEntity.ok(Map.of("type", PONG));
            case APPLICATION_COMMAND -> ResponseEntity.ok(reply(answer(interaction)));
            default -> ResponseEntity.badRequest().build();
        };
    }

    // A thrown exception would surface in Discord as a bare "interaction failed"
    private String answer(JsonNode interaction) {
        try {
            return handler.handle(interaction);
        } catch (Exception ex) {
            log.error("Slash command {} failed", interaction.path("data").path("name").asText("?"), ex);
            return "Something went wrong on my end. Try again in a minute.";
        }
    }

    private static Map<String, Object> reply(String content) {
        return Map.of(
                "type", CHANNEL_MESSAGE_WITH_SOURCE,
                "data", Map.of(
                        "content", content,
                        "flags", EPHEMERAL,
                        // Replies can echo what the user typed; none of it may ping anyone
                        "allowed_mentions", Map.of("parse", List.of())));
    }
}
