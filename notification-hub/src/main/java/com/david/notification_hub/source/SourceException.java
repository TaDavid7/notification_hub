package com.david.notification_hub.source;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * A source's API call failed. Carries what the poller needs to back off sensibly: the
 * HTTP status, if there was a response, and how long the API asked us to wait, if it said.
 */
public class SourceException extends RuntimeException {

    private static final int MAX_BODY = 300;

    private final SourceType source;
    private final Integer status;
    private final Duration retryAfter;

    public SourceException(SourceType source, String message) {
        this(source, message, null, null, null);
    }

    private SourceException(SourceType source, String message, Integer status, Duration retryAfter, Throwable cause) {
        super(source + ": " + message, cause);
        this.source = source;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /**
     * Translates a RestClient failure without quoting the request URL or passing
     * RestClient's own exception on. Ticketmaster takes its API key as a query parameter.
     * Spring 6.2 happens to leave the query string out of its I/O error message, but
     * keeping the key out of the logs shouldn't depend on that staying true.
     *
     * @param secrets values to blank out of anything the API sent back, in case an error
     *                body echoes the request
     */
    public static SourceException from(SourceType source, RestClientException e, String... secrets) {
        if (e instanceof RestClientResponseException r) {
            int code = r.getStatusCode().value();
            String body = redact(r.getResponseBodyAsString(), secrets);
            if (body.length() > MAX_BODY) body = body.substring(0, MAX_BODY) + "…";
            return new SourceException(source, "HTTP " + code + ": " + body,
                    code, retryAfter(r.getResponseHeaders()), null);
        }

        // Keep what actually went wrong underneath - a timeout, a refused connection - but
        // not e itself, whose message names the request
        Throwable cause = e.getMostSpecificCause();
        if (cause == e) {
            return new SourceException(source, "request failed: " + e.getClass().getSimpleName(), null, null, null);
        }
        String detail = cause.getMessage() == null ? "" : ": " + redact(cause.getMessage(), secrets);
        return new SourceException(source, "request failed: " + cause.getClass().getSimpleName() + detail,
                null, null, cause);
    }

    public SourceType getSource() { return source; }

    /** The HTTP status, or null when no response came back at all. */
    public Integer getStatus() { return status; }

    /** How long the API asked us to wait, or null when it didn't say. */
    public Duration getRetryAfter() { return retryAfter; }

    // Retry-After in seconds. The HTTP-date form is allowed by the spec but rare from
    // APIs, and the poller falls back to its own backoff when this is null.
    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String redact(String text, String... secrets) {
        String out = text == null ? "" : text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) out = out.replace(secret, "***");
        }
        return out;
    }
}
