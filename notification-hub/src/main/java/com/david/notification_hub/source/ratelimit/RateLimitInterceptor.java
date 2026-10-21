package com.david.notification_hub.source.ratelimit;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Map;

/**
 * Holds each outgoing request to its API's limits, chosen by host.
 *
 * Limits belong to the API, not to a source or a feed - one Ticketmaster fetch can be
 * five page requests - so they are applied here, to every request, and a source needs
 * no code for them. A host with no limit configured passes straight through.
 *
 * A short wait (a per-second limit) is slept off. A long one (the day's quota spent)
 * fails the request with {@link RateLimitExceededException}, so the poller can move on
 * and come back to that feed later instead of a thread sleeping for hours.
 */
public class RateLimitInterceptor implements ClientHttpRequestInterceptor {

    /** Sleeps; replaced in tests so they don't. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    // Longer than any per-second wait, far shorter than a day's
    static final Duration MAX_BLOCK = Duration.ofSeconds(5);

    private final Map<String, RateLimiter> byHost;
    private final Sleeper sleeper;

    public RateLimitInterceptor(Map<String, RateLimiter> byHost, Sleeper sleeper) {
        this.byHost = Map.copyOf(byHost);
        this.sleeper = sleeper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String host = request.getURI().getHost();
        RateLimiter limiter = host == null ? null : byHost.get(host);
        if (limiter != null) {
            Duration wait = limiter.tryAcquire();
            while (!wait.isZero()) {
                if (wait.compareTo(MAX_BLOCK) > 0) throw new RateLimitExceededException(host, wait);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted waiting for the " + host + " rate limit");
                }
                wait = limiter.tryAcquire();
            }
        }
        return execution.execute(request, body);
    }
}
