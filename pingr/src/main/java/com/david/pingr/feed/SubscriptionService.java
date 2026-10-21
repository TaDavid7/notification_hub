package com.david.pingr.feed;

import com.david.pingr.source.SourceType;
import com.david.pingr.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Subscribing, listing and unsubscribing, keyed by Discord user id. Users are created on
 * their first subscription.
 *
 * The writes use ON CONFLICT rather than catching unique violations, because a violation
 * aborts the surrounding Postgres transaction, and the same person double-clicking a
 * command is an ordinary case.
 */
@Service
public class SubscriptionService {

    public enum Status { CREATED, ALREADY_SUBSCRIBED, LIMIT_REACHED }

    /** @param feedId the new or existing feed; null when the limit was reached */
    public record Result(Status status, Long feedId) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final FeedRepository feeds;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final int maxFeedsPerUser;

    public SubscriptionService(NamedParameterJdbcTemplate jdbc,
                               FeedRepository feeds,
                               UserRepository users,
                               ObjectMapper mapper,
                               @Value("${alerts.maxFeedsPerUser:10}") int maxFeedsPerUser) {
        this.jdbc = jdbc;
        this.feeds = feeds;
        this.users = users;
        this.mapper = mapper;
        this.maxFeedsPerUser = maxFeedsPerUser;
    }

    /**
     * Subscribing to something you already have hands back that subscription, and doesn't
     * count against the limit. The limit exists because each distinct query is its own
     * poll against a rate-limited API.
     */
    @Transactional
    public Result subscribe(String discordUserId, SourceType type, Map<String, String> params) {
        long userId = userIdFor(discordUserId);
        String json = json(params);

        Long existing = existing(userId, type, json);
        if (existing != null) return new Result(Status.ALREADY_SUBSCRIBED, existing);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM feeds WHERE user_id = :userId",
                Map.of("userId", userId), Integer.class);
        if (count != null && count >= maxFeedsPerUser) return new Result(Status.LIMIT_REACHED, null);

        List<Long> created = jdbc.queryForList("""
                INSERT INTO feeds (user_id, source_type, params)
                VALUES (:userId, :type, CAST(:params AS jsonb))
                ON CONFLICT DO NOTHING
                RETURNING id
                """, Map.of("userId", userId, "type", type.name(), "params", json), Long.class);

        // Empty only if the same subscription was made concurrently since the check above
        return created.isEmpty()
                ? new Result(Status.ALREADY_SUBSCRIBED, existing(userId, type, json))
                : new Result(Status.CREATED, created.get(0));
    }

    /** Oldest first, so the numbers /list shows stay in the order they were made. */
    public List<Feed> list(String discordUserId) {
        return users.findByExternalId(discordUserId)
                .map(u -> feeds.findByUserIdOrderByIdAsc(u.getId()))
                .orElse(List.of());
    }

    /** @return false when the feed doesn't exist or belongs to someone else */
    @Transactional
    public boolean unsubscribe(String discordUserId, long feedId) {
        int removed = jdbc.update("""
                DELETE FROM feeds
                 WHERE id = :feedId
                   AND user_id = (SELECT id FROM users WHERE external_id = :discordUserId)
                """, Map.of("feedId", feedId, "discordUserId", discordUserId));
        return removed == 1;
    }

    private long userIdFor(String discordUserId) {
        Map<String, Object> params = Map.of("discordUserId", discordUserId);
        jdbc.update("INSERT INTO users (external_id) VALUES (:discordUserId) ON CONFLICT (external_id) DO NOTHING",
                params);
        return jdbc.queryForObject("SELECT id FROM users WHERE external_id = :discordUserId", params, Long.class);
    }

    // jsonb equality ignores key order, so this finds the subscription however it was written
    private Long existing(long userId, SourceType type, String json) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM feeds
                 WHERE user_id = :userId AND source_type = :type AND params = CAST(:params AS jsonb)
                """, Map.of("userId", userId, "type", type.name(), "params", json), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String json(Map<String, String> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("feed params could not be written as JSON", e);
        }
    }
}
