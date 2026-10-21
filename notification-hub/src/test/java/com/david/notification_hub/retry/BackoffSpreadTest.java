package com.david.notification_hub.retry;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exponential half is covered through OutboxStore by outbox.BackoffTest. */
class BackoffSpreadTest {

    @RepeatedTest(50)
    void staysWithinTenPercentOfTheInterval() {
        long ms = Backoff.spread(Duration.ofMinutes(60)).toMillis();

        assertTrue(ms >= Duration.ofMinutes(54).toMillis(), "too early: " + ms);
        assertTrue(ms <= Duration.ofMinutes(66).toMillis(), "too late: " + ms);
    }
}
