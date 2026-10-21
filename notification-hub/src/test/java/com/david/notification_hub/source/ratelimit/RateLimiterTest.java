package com.david.notification_hub.source.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Driven by a clock the test moves by hand, so no test sleeps. */
class RateLimiterTest {

    private final MovableClock clock = new MovableClock();

    @Test
    void allowsThePerSecondLimitThenSaysHowLongToWait() {
        RateLimiter limiter = new RateLimiter(2, 0, clock);

        assertEquals(Duration.ZERO, limiter.tryAcquire());
        clock.advance(Duration.ofMillis(300));
        assertEquals(Duration.ZERO, limiter.tryAcquire());

        // Full: the first slot frees a second after the first request
        assertEquals(Duration.ofMillis(700), limiter.tryAcquire());

        clock.advance(Duration.ofMillis(700));
        assertEquals(Duration.ZERO, limiter.tryAcquire());
    }

    @Test
    void aRefusedRequestIsNotCounted() {
        RateLimiter limiter = new RateLimiter(1, 0, clock);
        limiter.tryAcquire();

        for (int i = 0; i < 5; i++) limiter.tryAcquire();   // all refused
        clock.advance(Duration.ofSeconds(1));

        assertEquals(Duration.ZERO, limiter.tryAcquire(), "refusals must not push the window out");
    }

    @Test
    void theDailyLimitHoldsOverAnyTwentyFourHours() {
        RateLimiter limiter = new RateLimiter(0, 3, clock);
        limiter.tryAcquire();
        clock.advance(Duration.ofHours(10));
        limiter.tryAcquire();
        limiter.tryAcquire();

        // Not a calendar day: the slot comes back 24h after the oldest request, 14h from now
        assertEquals(Duration.ofHours(14), limiter.tryAcquire());

        clock.advance(Duration.ofHours(14));
        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertTrue(limiter.tryAcquire().compareTo(Duration.ofHours(9)) > 0, "the other two are still in the window");
    }

    @Test
    void theLongerOfTwoWaitsWins() {
        RateLimiter limiter = new RateLimiter(5, 1, clock);
        limiter.tryAcquire();

        assertEquals(Duration.ofDays(1), limiter.tryAcquire());
    }

    @Test
    void zeroMeansNoLimit() {
        RateLimiter limiter = new RateLimiter(0, 0, clock);

        for (int i = 0; i < 10_000; i++) assertEquals(Duration.ZERO, limiter.tryAcquire());
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
