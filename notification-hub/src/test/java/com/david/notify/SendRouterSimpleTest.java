package com.david.notification_hub.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
<<<<<<< HEAD
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
=======


class SendRouterSimpleTest {

    static class FakeDiscordSender implements Sender {
        boolean called = false;
        @Override
        public SendResult send(String token, String title, String body, boolean sandbox) {
            called = true;
            return SendResult.ok("ok");
        }
    }

    static class FakeSlackSender implements Sender {
        boolean called = false;
        @Override
        public SendResult send(String token, String title, String body, boolean sandbox) {
            called = true;
            return SendResult.ok("ok");
        }
    }

    @Test
    void routesToDiscordWhenChannelIsDISCORD() {
        FakeDiscordSender discord = new FakeDiscordSender();
        FakeSlackSender slack = new FakeSlackSender();

        SendRouter router = new SendRouter(discord, slack);

        SendResult result = router.send("DISCORD", "", "Hello", "World", true);

        assertTrue(result.success, "Discord send should succeed");
        assertTrue(discord.called, "Discord sender should be called");
        assertFalse(slack.called, "Slack sender should NOT be called");
    }

    @Test
    void routesToSlackWhenChannelIsSLACK() {
        FakeDiscordSender discord = new FakeDiscordSender();
        FakeSlackSender slack = new FakeSlackSender();
        SendRouter router = new SendRouter(discord, slack);

        SendResult result = router.send("SLACK", "", "Hello", "World", true);

        assertTrue(result.success);
        assertTrue(slack.called, "Slack sender should be called");
        assertFalse(discord.called, "Discord sender should NOT be called");
    }

    @Test
    void throwsOnUnknownChannel() {
        FakeDiscordSender discord = new FakeDiscordSender();
        FakeSlackSender slack = new FakeSlackSender();
        SendRouter router = new SendRouter(discord, slack);


        assertThrows(IllegalArgumentException.class, () ->
                router.send("SMS", "", "Hello", "World", true)
        );
>>>>>>> 8bb1db574ed053f322438f8a90633466c09f81cf
    }
}
