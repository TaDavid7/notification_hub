package com.david.pingr.notify;

import org.springframework.stereotype.Component;

@Component
public class SendRouter{
    private final DiscordSender discord;
    private final SlackSender slack;

    public SendRouter(DiscordSender d, SlackSender s){
        this.discord = d;
        this.slack = s;
    }

    public SendResult send(String channel, String token, String title, String body){
        String ch = channel;
        if(channel == null){
            ch = "DISCORD";
        } else{
            ch = channel.toUpperCase();
        }
        return switch (ch){
            case "DISCORD" -> discord.send(token, title, body);
            case "SLACK" -> slack.send(token, title, body);
            default -> SendResult.fail("Unknown channel: " + channel);
        };
    }


}