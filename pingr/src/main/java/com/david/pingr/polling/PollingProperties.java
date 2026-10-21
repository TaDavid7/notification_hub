package com.david.pingr.polling;

import com.david.pingr.source.SourceType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * The {@code polling.*} settings. A source with no interval of its own uses the default,
 * and an API host with no rate limit is not limited - so a new source works without any
 * of this, and only needs an entry to be tuned.
 *
 * @param rateLimits keyed by API host. Hosts contain dots, so in YAML each key must be
 *                   bracketed - "[app.ticketmaster.com]" - or Spring reads the dots as
 *                   nesting and the limit silently never applies.
 */
@ConfigurationProperties("polling")
public record PollingProperties(
        @DefaultValue("10") int batchSize,
        @DefaultValue("600") long leaseSeconds,
        @DefaultValue("300") long baseBackoffSeconds,
        @DefaultValue("21600") long maxBackoffSeconds,
        @DefaultValue("60") long defaultIntervalMinutes,
        Map<SourceType, Long> intervalMinutes,
        Map<String, RateLimit> rateLimits) {

    public PollingProperties {
        intervalMinutes = intervalMinutes == null ? Map.of() : Map.copyOf(intervalMinutes);
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
    }

    /** Zero means no limit of that kind. */
    public record RateLimit(@DefaultValue("0") int perSecond, @DefaultValue("0") int perDay) {}

    public Duration intervalFor(SourceType type) {
        return Duration.ofMinutes(intervalMinutes.getOrDefault(type, defaultIntervalMinutes));
    }

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    public Duration baseBackoff() {
        return Duration.ofSeconds(baseBackoffSeconds);
    }

    public Duration maxBackoff() {
        return Duration.ofSeconds(maxBackoffSeconds);
    }
}
