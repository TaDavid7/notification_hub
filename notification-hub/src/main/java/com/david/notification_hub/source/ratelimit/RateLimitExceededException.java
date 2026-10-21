package com.david.notification_hub.source.ratelimit;

import java.io.IOException;
import java.time.Duration;

/**
 * A request was held back because its API's allowance is spent for longer than is worth
 * waiting for. An IOException because that is what a RestClient interceptor may throw;
 * it reaches the source wrapped in a ResourceAccessException.
 */
public class RateLimitExceededException extends IOException {

    private final Duration retryAfter;

    public RateLimitExceededException(String host, Duration retryAfter) {
        super("rate limit for " + host + " reached; next request allowed in " + retryAfter.toSeconds() + "s");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
