package com.david.pingr.polling;

import com.david.pingr.feed.Feed;
import com.david.pingr.feed.FeedRepository;
import com.david.pingr.retry.Backoff;
import com.david.pingr.source.SourceType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Every database step the poller takes, each short. A separate bean from
 * {@link PollScheduler} for the reason OutboxStore gives: {@code @Transactional} works
 * through a proxy, so the scheduler calling its own methods would get no transaction.
 *
 * The API call happens between {@link #claimDue} and the record methods, with no
 * transaction open.
 */
@Service
public class PollStore {

    // Error text is for a person reading the table; a whole HTML error page isn't
    private static final int MAX_ERROR = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final FeedRepository feeds;

    public PollStore(NamedParameterJdbcTemplate jdbc, FeedRepository feeds) {
        this.jdbc = jdbc;
        this.feeds = feeds;
    }

    /**
     * Brings poll_groups in line with feeds: a row for every distinct query, none for a
     * query nobody has any more. Cheap enough to run every pass, which means subscribing
     * and unsubscribing never have to touch this table.
     */
    @Transactional
    public void syncGroups() {
        jdbc.update("""
                INSERT INTO poll_groups (source_type, params)
                SELECT DISTINCT source_type, params FROM feeds
                ON CONFLICT (source_type, params) DO NOTHING
                """, Map.of());
        jdbc.update("""
                DELETE FROM poll_groups g
                 WHERE NOT EXISTS (SELECT 1 FROM feeds f
                                    WHERE f.source_type = g.source_type AND f.params = g.params)
                """, Map.of());
    }

    /**
     * Takes up to {@code limit} due groups and pushes each one's next_poll_at out by the
     * lease, so no other pass - or instance - takes it meanwhile. {@code FOR UPDATE SKIP
     * LOCKED} makes two instances claiming at the same moment take different groups.
     * REQUIRES_NEW so the row locks end with the claim, not with the polls after it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PollGroup> claimDue(Collection<SourceType> types, Duration lease, int limit) {
        if (types.isEmpty()) return List.of();
        OffsetDateTime now = OffsetDateTime.now();

        List<PollGroup> due = jdbc.query("""
                SELECT id, source_type, params::text AS params, failures
                  FROM poll_groups
                 WHERE next_poll_at <= :now AND source_type IN (:types)
                 ORDER BY next_poll_at
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """,
                Map.of("now", now, "types", types.stream().map(Enum::name).toList(), "limit", limit),
                (rs, i) -> new PollGroup(rs.getLong("id"), SourceType.valueOf(rs.getString("source_type")),
                        rs.getString("params"), rs.getInt("failures")));

        if (!due.isEmpty()) {
            jdbc.update("UPDATE poll_groups SET next_poll_at = :leaseEnds WHERE id IN (:ids)",
                    Map.of("leaseEnds", now.plus(lease), "ids", due.stream().map(PollGroup::id).toList()));
        }
        return due;
    }

    /** The feeds asking this group's query right now - read fresh, so a subscribe since the claim counts. */
    public List<Feed> feedsOf(PollGroup group) {
        return feeds.findGroup(group.sourceType().name(), group.paramsJson());
    }

    /** Polled fine: back to the regular interval, spread a little so groups don't bunch up. */
    public void recordSuccess(PollGroup group, Duration interval) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                UPDATE poll_groups
                   SET failures = 0, last_error = NULL, last_polled_at = :now, next_poll_at = :next
                 WHERE id = :id
                """, Map.of("id", group.id(), "now", now, "next", now.plus(Backoff.spread(interval))));
    }

    /**
     * The API call failed. Backs off exponentially, but never sooner than the API asked.
     *
     * @return how long until the next try
     */
    public Duration recordFailure(PollGroup group, String error, Duration retryAfter, Duration base, Duration max) {
        Duration wait = Backoff.exponential(group.failures() + 1, base, max);
        if (retryAfter != null && retryAfter.compareTo(wait) > 0) wait = retryAfter;

        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                UPDATE poll_groups
                   SET failures = failures + 1, last_error = :error, last_polled_at = :now, next_poll_at = :next
                 WHERE id = :id
                """, Map.of("id", group.id(), "error", truncate(error), "now", now, "next", now.plus(wait)));
        return wait;
    }

    /**
     * Not polled, through no fault of the API's - our own rate limit is spent. Comes back
     * when it allows, without counting as a failure.
     */
    public void defer(PollGroup group, Duration wait, String reason) {
        jdbc.update("UPDATE poll_groups SET next_poll_at = :next, last_error = :reason WHERE id = :id",
                Map.of("id", group.id(), "next", OffsetDateTime.now().plus(wait), "reason", truncate(reason)));
    }

    private static String truncate(String s) {
        if (s == null) return "unknown error";
        return s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR) + "…";
    }
}
