package com.david.notification_hub.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends messages to a Discord webhook.
 *
 * Success:
 *  - 204 No Content (default webhook behavior)
 *  - 200 OK (when using ?wait=true)
 *
 * Rate limiting:
 *  - On 429, waits for "retry_after" (JSON seconds) or "Retry-After" header, then retries once.
 */
@Component
public class DiscordSender implements Sender {

    private final HttpClient http;
    private final String configuredWebhookUrl;

    // Discord hard limits
    private static final int MAX_CONTENT = 2000; // characters
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(10);

    public DiscordSender(@Value("${discord.webhookUrl:}") String webhookUrl) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.configuredWebhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @Override
    public SendResult send(String token, String title, String body, boolean isSandbox) {
        // Prefer per-call token if provided; otherwise use configured URL
        String baseUrl = (token != null && !token.isBlank()) ? token.trim() : configuredWebhookUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            return SendResult.fail("Discord webhook URL is not configured.");
        }

        // Ask Discord to return a body -> 200, but we still treat 204 as success
        String url = ensureWaitTrue(baseUrl);

        // Build a readable content payload within Discord's limits
        String content = buildContent(title, body, isSandbox);
        String json = "{\"content\":\"" + escapeJson(content) + "\"}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQ_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();

            if (isSuccess(code)) {
                return SendResult.ok("Discord OK " + code);
            }

            if (code == 429) {
                long backoffMs = parseRetryAfterMs(res);
                sleep(backoffMs);

                HttpResponse<String> res2 = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code2 = res2.statusCode();
                if (isSuccess(code2)) {
                    return SendResult.ok("Discord OK after retry " + code2);
                }
                return SendResult.fail("Discord 429 then " + code2 + ": " + safeBody(res2));
            }

            return SendResult.fail("Discord HTTP " + code + ": " + safeBody(res));
        } catch (IOException e) {
            return SendResult.fail("Discord IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.fail("Discord interrupted");
        } catch (Exception e) {
            return SendResult.fail("Discord error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static boolean isSuccess(int code) {
        // Webhook default: 204; with ?wait=true: 200
        return code == 204 || code == 200;
    }

    private static String ensureWaitTrue(String url) {
        String u = url.trim();
        // Avoid double appending
        if (u.contains("?")) {
            // already has query, add only if not present
            return u.toLowerCase(Locale.ROOT).contains("wait=true") ? u : (u + "&wait=true");
        }
        return u + "?wait=true";
    }

    private static String buildContent(String title, String body, boolean isSandbox) {
        String prefix = isSandbox ? "🧪 [SANDBOX]\n" : "";
        String t = (title == null || title.isBlank()) ? "" : ("**" + title.trim() + "**\n");
        String b = (body == null) ? "" : body.trim();

        String combined = prefix + t + b;
        if (combined.length() <= MAX_CONTENT) return combined;

        // Trim with a clear ellipsis
        String ellipsis = "\n… (truncated)";
        int max = Math.max(0, MAX_CONTENT - ellipsis.length());
        return combined.substring(0, max) + ellipsis;
    }

    private static String escapeJson(String s) {
        // minimal, safe JSON string escaper for our simple payload
        StringBuilder sb = new StringBuilder((int) (s.length() * 1.1));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String safeBody(HttpResponse<String> res) {
        String b = res.body();
        if (b == null || b.isBlank()) return "<no body>";
        // limit log spam
        return b.length() > 500 ? b.substring(0, 500) + "…(truncated)" : b;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static final Pattern RETRY_AFTER_JSON = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    private static long parseRetryAfterMs(HttpResponse<String> res) {
        // Prefer header if present (seconds)
        String hdr = res.headers().firstValue("Retry-After").orElse(null);
        if (hdr != null) {
            try {
                double sec = Double.parseDouble(hdr.trim());
                return (long) ((sec + 0.05) * 1000); // small cushion
            } catch (NumberFormatException ignored) { /* fall back to JSON */ }
        }
        // Parse JSON body field retry_after (seconds, may be fractional)
        String body = res.body();
        if (body != null) {
            Matcher m = RETRY_AFTER_JSON.matcher(body);
            if (m.find()) {
                try {
                    double sec = Double.parseDouble(m.group(1));
                    return (long) ((sec + 0.05) * 1000); // small cushion
                } catch (NumberFormatException ignored) {}
            }
        }
        // safe default backoff
        return 1100L;
    }
}
