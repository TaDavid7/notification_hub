package com.david.pingr.source.ratelimit;

import com.david.pingr.source.SourceException;
import com.david.pingr.source.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The interceptor on a real RestClient, with MockRestServiceServer behind it. Its sleeps
 * are recorded and advance a fake clock instead of waiting.
 */
class RateLimitInterceptorTest {

    private final SteppingClock clock = new SteppingClock();
    private final List<Duration> slept = new ArrayList<>();
    private final RateLimitInterceptor.Sleeper sleeper = d -> {
        slept.add(d);
        clock.advance(d);
    };

    @Test
    void aShortWaitIsSleptOffAndTheRequestStillGoes() {
        RestClient client = client(Map.of("limited.test", new RateLimiter(1, 0, clock)), "https://limited.test", 2);

        client.get().uri("/a").retrieve().toBodilessEntity();
        client.get().uri("/b").retrieve().toBodilessEntity();

        assertEquals(List.of(Duration.ofSeconds(1)), slept);
    }

    @Test
    void aLongWaitFailsTheRequestRatherThanBlockingForHours() {
        RestClient client = client(Map.of("limited.test", new RateLimiter(0, 1, clock)), "https://limited.test", 1);
        client.get().uri("/a").retrieve().toBodilessEntity();

        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client.get().uri("/b").retrieve().toBodilessEntity());

        assertTrue(slept.isEmpty());
        // ...and a source turns it into a throttled SourceException, so the poller defers
        SourceException translated = SourceException.from(SourceType.TICKETMASTER, ex);
        assertTrue(translated.isThrottled());
        assertEquals(Duration.ofDays(1), translated.getRetryAfter());
    }

    @Test
    void aHostWithNoLimitIsNotHeldBack() {
        RestClient client = client(Map.of("limited.test", new RateLimiter(1, 1, clock)), "https://free.test", 5);

        for (int i = 0; i < 5; i++) client.get().uri("/x").retrieve().toBodilessEntity();

        assertTrue(slept.isEmpty());
    }

    private RestClient client(Map<String, RateLimiter> limits, String baseUrl, int expectedRequests) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new RateLimitInterceptor(limits, sleeper));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(expectedRequests), requestTo(startsWith(baseUrl)))
                .andRespond(withSuccess());
        return builder.build();
    }

    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
