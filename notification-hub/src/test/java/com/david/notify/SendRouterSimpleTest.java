package com.david.notification_hub.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SendRouterSimpleTest {

    @Test
    void routesDiscord() {
        DiscordSender discord = mock(DiscordSender.class);
        SlackSender slack = mock(SlackSender.class);
        when(discord.send(any(), any(), any(), anyBoolean())).thenReturn(SendResult.ok("ok"));

        SendRouter router = new SendRouter(discord, slack);
        SendResult r = router.send("DISCORD", "", "t", "b", true);

        assertTrue(r.success);
        verify(discord).send(eq(""), eq("t"), eq("b"), eq(true));
        verifyNoInteractions(slack);
    }

    @Test
    void routesSlack() {
        DiscordSender discord = mock(DiscordSender.class);
        SlackSender slack = mock(SlackSender.class);
        when(slack.send(any(), any(), any(), anyBoolean())).thenReturn(SendResult.ok("ok"));

        SendRouter router = new SendRouter(discord, slack);
        SendResult r = router.send("SLACK", "", "t", "b", true);

        assertTrue(r.success);
        verify(slack).send(eq(""), eq("t"), eq("b"), eq(true));
        verifyNoInteractions(discord);
    }

    @Test
    void unknownChannelThrows() {
        DiscordSender discord = mock(DiscordSender.class);
        SlackSender slack = mock(SlackSender.class);
        SendRouter router = new SendRouter(discord, slack);

        SendResult r = router.send("SMS", "", "t", "b", true);
        assertFalse(r.success);
        verifyNoInteractions(discord, slack);
    }
}
