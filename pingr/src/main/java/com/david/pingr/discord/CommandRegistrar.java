package com.david.pingr.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Registers the slash commands with Discord when the app starts.
 *
 * Uses the bulk-overwrite call, which replaces the whole set, so registering the same
 * commands again on every start - or from every instance - changes nothing.
 *
 * With DISCORD_GUILD_ID set the commands go to that one server, where changes show up
 * at once; that suits development. Without it they are global, which can take up to an
 * hour to reach every server.
 */
@Component
public class CommandRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrar.class);

    private final RestClient http;
    private final String applicationId;
    private final String botToken;
    private final String guildId;

    public CommandRegistrar(
            RestClient.Builder builder,
            @Value("${discord.apiBaseUrl:https://discord.com/api/v10}") String apiBaseUrl,
            @Value("${discord.applicationId:}") String applicationId,
            @Value("${discord.botToken:}") String botToken,
            @Value("${discord.guildId:}") String guildId) {
        this.http = builder.baseUrl(apiBaseUrl).build();
        this.applicationId = applicationId == null ? "" : applicationId.strip();
        this.botToken = botToken == null ? "" : botToken.strip();
        this.guildId = guildId == null ? "" : guildId.strip();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        register();
    }

    /**
     * Failing here is logged, not thrown: alerts still go out without it, and commands
     * registered by an earlier start keep working.
     *
     * @return true if Discord accepted the commands
     */
    public boolean register() {
        if (applicationId.isEmpty() || botToken.isEmpty()) {
            log.info("Slash commands not registered (set DISCORD_APPLICATION_ID and DISCORD_BOT_TOKEN).");
            return false;
        }

        boolean toGuild = !guildId.isEmpty();
        String path = toGuild
                ? "/applications/{app}/guilds/{guild}/commands"
                : "/applications/{app}/commands";
        try {
            http.put()
                    .uri(path, Map.of("app", applicationId, "guild", guildId))
                    // The token travels in this header, never in the url, so it stays out of
                    // any exception message
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(DiscordCommands.definitions())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Registering slash commands failed: {}", e.getMessage());
            return false;
        }

        log.info("Registered slash commands {}", toGuild
                ? "in guild " + guildId
                : "globally (they can take up to an hour to appear)");
        return true;
    }
}
