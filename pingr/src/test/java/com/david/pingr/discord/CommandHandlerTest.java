package com.david.pingr.discord;

import com.david.pingr.feed.SubscriptionService;
import com.david.pingr.geo.ZipCodes;
import com.david.pingr.source.SourceRegistry;
import com.david.pingr.source.ticketmaster.TicketmasterSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The handler without the app around it, for what InteractionControllerTest's fully
 * configured context can't show: a source that isn't set up.
 */
class CommandHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);

    @Test
    void concertsAreRefusedWhenTicketmasterHasNoKey() throws Exception {
        TicketmasterSource keyless = new TicketmasterSource(RestClient.builder(), "https://tm.test", "", 200, 5);
        CommandHandler handler = new CommandHandler(subscriptions, mock(ZipCodes.class),
                new SourceRegistry(List.of(keyless)));

        String reply = handler.handle(mapper.readTree("""
                {"type":2,"member":{"user":{"id":"123"}},"data":{"name":"subscribe","options":[
                  {"type":1,"name":"concerts","options":[
                    {"name":"zip","type":3,"value":"94110"},{"name":"radius","type":4,"value":25}]}]}}
                """));

        // Subscribing would succeed and then never hear a thing
        assertTrue(reply.contains("Concert alerts aren't available"), reply);
        verifyNoInteractions(subscriptions);
    }
}
