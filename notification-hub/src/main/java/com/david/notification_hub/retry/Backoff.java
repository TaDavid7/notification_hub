package com.david.notification_hub.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long to wait before trying something again, shared by the outbox's retries and
 * the poller's.
 */
public final class Backoff {

    private Backoff() {}

    /**
     * Exponential backoff with jitter: base * 2^(attempt-1), capped, then randomised
     * across [50%, 100%]. The jitter matters because one outage fails many things at
     * once - without it they would all come back in the same instant.
     */
    public static Duration exponential(int attempt, Duration base, Duration max) {
        long baseMs = base.toMillis();
        int shift = Math.min(attempt - 1, 20); // 2^20 is already far past any sane cap
        long scaled = baseMs << shift;
        if (scaled <= 0 || scaled > max.toMillis()) scaled = max.toMillis();

        long jittered = scaled / 2 + ThreadLocalRandom.current().nextLong(scaled / 2 + 1);
        return Duration.ofMillis(jittered);
    }

    /**
     * A regular interval spread by up to 10% either way, so things scheduled together
     * drift apart instead of firing together forever.
     */
    public static Duration spread(Duration interval) {
        long ms = interval.toMillis();
        long tenth = ms / 10;
        return Duration.ofMillis(ms - tenth + ThreadLocalRandom.current().nextLong(2 * tenth + 1));
    }
}
