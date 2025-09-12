package com.david.notification_hub.notify;

import org.springframework.stereotype.Component;

@Component
public class SendRouter{
    private final DiscordSender discord;
    private final SlackSender slack;

    public SendRouter(DiscordSender d, SlackSender s){
        this.discord = d;
        this.slack = s;
    }

    public SendResult send(String channel, String token, String title, String body, boolean isSandbox){
        String ch = channel;
        if(channel == null){
            channel = "DISCORD";
        } else{
            channel.toUpperCase();
        }
        return switch (ch){
            case "DISCORD" -> discord.send(token, title, body, isSandbox);
            case "SLACK" -> slack.send(token, title, body, isSandbox);
            default -> SendResult.fail("Unknown channel: " + channel);
        };
    }


}