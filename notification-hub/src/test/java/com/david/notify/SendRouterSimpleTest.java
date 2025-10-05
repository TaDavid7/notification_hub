package com.david.notification_hub.notify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


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
    }
}
