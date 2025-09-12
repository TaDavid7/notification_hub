package com.david.notification_hub.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class DiscordSender implements Sender {
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${discord.webhookUrl}")
    private String url;

    @Override
    public SendResult send(String token, String title, String body, boolean isSandbox) {
        String text = title + " — " + body;
        String json = "{\"content\":\"" + escape(text) + "\"}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                return SendResult.ok("DISCORD_DELIVERED");
            } else {
                return SendResult.fail("HTTP " + res.statusCode());
            }
        } catch (Exception e) {
            return SendResult.fail(e.getMessage());
        }
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "\\\"");
    }
}
