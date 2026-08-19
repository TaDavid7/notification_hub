package com.david.notification_hub.outbox;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit test - the backoff maths needs no Spring context or database. */
class BackoffTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofHours(1);

    @Test
    void growsWithEachAttempt() {
        // Jitter makes exact values meaningless, so compare the ceilings: attempt n
        // can never exceed 2^(n-1) * base.
        for (int attempt = 1; attempt <= 5; attempt++) {
            long ceiling = BASE.toMillis() << (attempt - 1);
            assertTrue(OutboxStore.backoffFor(attempt, BASE, MAX).toMillis() <= ceiling,
                    "attempt " + attempt + " exceeded its ceiling");
        }
    }

    @Test
    void isAlwaysPositiveSoARetryNeverBecomesASpin() {
        for (int attempt = 1; attempt <= 40; attempt++) {
            assertTrue(OutboxStore.backoffFor(attempt, BASE, MAX).toMillis() > 0,
                    "attempt " + attempt + " produced a non-positive delay");
        }
    }

    @Test
    void neverExceedsTheCapEvenWhenTheShiftWouldOverflow() {
        // A large attempt count shifts past Long range; the cap has to hold anyway
        for (int attempt : new int[]{10, 30, 63, 100, Integer.MAX_VALUE}) {
            assertTrue(OutboxStore.backoffFor(attempt, BASE, MAX).toMillis() <= MAX.toMillis(),
                    "attempt " + attempt + " blew past the cap");
        }
    }

    @RepeatedTest(20)
    void jitterStaysWithinHalfToFullOfTheScaledDelay() {
        long delay = OutboxStore.backoffFor(3, BASE, MAX).toMillis();
        long scaled = BASE.toMillis() << 2;   // 2^(3-1)

        assertTrue(delay >= scaled / 2, "jitter dropped below the 50% floor: " + delay);
        assertTrue(delay <= scaled, "jitter rose above the scaled delay: " + delay);
    }
}
