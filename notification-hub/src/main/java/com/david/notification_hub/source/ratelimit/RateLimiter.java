package com.david.notification_hub.source.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Request limits for one API: at most {@code perSecond} in any second and
 * {@code perDay} in any 24 hours. Zero means no limit of that kind.
 *
 * A sliding log rather than a token bucket: it remembers when each recent request went,
 * so the limit holds over every window, not just on average. A bucket that starts full
 * can spend a day's quota at startup and another day's as it refills. The day's log is
 * at most perDay timestamps, a few thousand.
 *
 * This counts one instance's requests, from its last start. Several instances, or a
 * restart, each get the full allowance; a limit shared between instances needs shared
 * state (the Redis item in plan.md).
 */
public final class RateLimiter {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final int perSecond;
    private final int perDay;
    private final Clock clock;
    private final Deque<Instant> lastSecond = new ArrayDeque<>();
    private final Deque<Instant> lastDay = new ArrayDeque<>();

    public RateLimiter(int perSecond, int perDay, Clock clock) {
        this.perSecond = perSecond;
        this.perDay = perDay;
        this.clock = clock;
    }

    /**
     * Takes a slot if one is free.
     *
     * @return zero if the request may go now (and it has been counted), otherwise how
     *         long until it may
     */
    public synchronized Duration tryAcquire() {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        if (perSecond > 0) wait = max(wait, waitFor(lastSecond, perSecond, SECOND, now));
        if (perDay > 0) wait = max(wait, waitFor(lastDay, perDay, DAY, now));
        if (!wait.isZero()) return wait;

        if (perSecond > 0) lastSecond.addLast(now);
        if (perDay > 0) lastDay.addLast(now);
        return Duration.ZERO;
    }

    // Forgets requests older than the window; if the window is still full, the next slot
    // opens when its oldest request ages out
    private static Duration waitFor(Deque<Instant> log, int limit, Duration window, Instant now) {
        Instant cutoff = now.minus(window);
        while (!log.isEmpty() && !log.peekFirst().isAfter(cutoff)) log.removeFirst();
        if (log.size() < limit) return Duration.ZERO;
        return Duration.between(now, log.peekFirst().plus(window));
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
