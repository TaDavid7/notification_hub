package com.david.pingr.alert;

import com.david.pingr.event.StoredEvent;
import com.david.pingr.event.StoredEventRepository;
import com.david.pingr.feed.Feed;
import com.david.pingr.notification_request.NotificationIntakeService;
import com.david.pingr.source.Event;
import com.david.pingr.source.EventStatus;
import com.david.pingr.user.User;
import com.david.pingr.user.UserRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records one fetched event and enqueues whatever alerts it calls for, in a single
 * transaction - the transactional outbox doing its job. If any of it fails, none of it
 * happened: the stored event keeps its old hash, so the next poll sees the same change
 * and tries again, rather than the change being saved and its alert lost.
 *
 * A separate bean from {@link ChangeDetector} for the reason OutboxStore gives:
 * {@code @Transactional} works through a proxy, so the loop calling this on itself
 * would get no transaction at all.
 *
 * Who hears what:
 * <ul>
 *   <li>NEW goes to users whose feed is linked to the event for the first time. It is
 *       per user, not per event: two overlapping areas are two groups, and whichever
 *       polls second must still tell its own subscribers. A feed still on its first
 *       poll is linked silently instead; {@link #finishBaseline} sums up for it.</li>
 *   <li>An UPDATE goes to everyone already following the event through any feed, not
 *       just the group whose poll noticed - the other groups will see no change when
 *       their turn comes.</li>
 * </ul>
 */
@Service
public class EventTracker {

    // Subscriptions come from Discord, so their alerts go there
    private static final String CHANNEL = "DISCORD";

    private final StoredEventRepository events;
    private final UserRepository users;
    private final NotificationIntakeService intake;
    private final NamedParameterJdbcTemplate jdbc;

    public EventTracker(StoredEventRepository events,
                        UserRepository users,
                        NotificationIntakeService intake,
                        NamedParameterJdbcTemplate jdbc) {
        this.events = events;
        this.users = users;
        this.intake = intake;
        this.jdbc = jdbc;
    }

    /**
     * @param created true when this was the first time any feed saw the event
     * @param changed true when its alertable content differed from what was stored
     * @param alerts  how many outbox rows were written
     */
    public record Outcome(boolean created, boolean changed, int alerts) {}

    /**
     * @param feeds the group that fetched it: feeds sharing a source type and params
     *
     * Two groups inserting the same new event at the same moment collide on its unique
     * key; the loser's transaction rolls back and its next poll picks the event up.
     */
    @Transactional
    public Outcome record(List<Feed> feeds, Event fetched) {
        StoredEvent stored = events.findBySourceAndExternalId(fetched.source(), fetched.externalId()).orElse(null);
        boolean created = stored == null;
        EventSnapshot before = null;

        if (created) {
            stored = new StoredEvent();
            stored.setSource(fetched.source());
            stored.setExternalId(fetched.externalId());
            copy(fetched, stored);
            stored = events.save(stored);
        } else {
            if (!stored.getContentHash().equals(fetched.contentHash())) {
                before = EventSnapshot.of(stored);
                stored.setRevision(stored.getRevision() + 1);
            }
            // Title and url are kept current even when nothing worth an alert changed
            copy(fetched, stored);
        }

        // Read before linking, so this is exactly the people who already knew of it
        Set<Long> followers = before == null ? Set.of() : followers(stored.getId());

        Map<Long, Feed> feedById = new HashMap<>();
        for (Feed f : feeds) feedById.put(f.getId(), f);
        Set<Long> newcomers = new HashSet<>();
        for (Long feedId : link(feedById.keySet(), stored.getId())) {
            Feed f = feedById.get(feedId);
            // Existing events found by a feed's first poll are what was already there when
            // its owner subscribed: linked, so changes reach them, but not announced
            if (f.getBaselinedAt() != null) newcomers.add(f.getUserId());
        }

        Set<Long> everyone = new HashSet<>(followers);
        everyone.addAll(newcomers);
        Map<Long, String> discordIds = discordIds(everyone);

        int alerts = 0;
        // Hearing about an event for the first time as already cancelled is just noise
        if (stored.getStatus() != EventStatus.CANCELED) {
            for (Long userId : newcomers) {
                alerts += enqueue(Alerts.newEvent(stored, discordIds.get(userId)), userId, discordIds);
            }
        }
        if (before != null) {
            for (Long userId : followers) {
                alerts += enqueue(Alerts.updated(stored, before, discordIds.get(userId)), userId, discordIds);
            }
        }
        return new Outcome(created, before != null, alerts);
    }

    private static void copy(Event from, StoredEvent to) {
        to.setTitle(from.title());
        to.setStartsAt(from.startsAt());
        to.setOnSaleAt(from.onSaleAt());
        to.setVenue(from.venue());
        to.setPrice(from.price());
        to.setUrl(from.url());
        to.setStatus(from.status());
        to.setContentHash(from.contentHash());
    }

    // Users following the event through any feed
    private Set<Long> followers(long eventId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT f.user_id
                  FROM feed_events fe
                  JOIN feeds f ON f.id = fe.feed_id
                 WHERE fe.event_id = :eventId
                """, Map.of("eventId", eventId), Long.class));
    }

    /**
     * Ends a feed's first poll: one summary of what it found, and the feed marked so
     * later polls announce new items individually. Both or neither, so a failure here
     * just means the next poll tries the summary again.
     *
     * @param matches what the source returned on this poll
     * @return how many outbox rows were written
     */
    @Transactional
    public int finishBaseline(Feed feed, List<Event> matches) {
        String discordId = users.findById(feed.getUserId()).map(User::getExternalId).orElse(null);
        int alerts = 0;
        if (discordId != null) {
            Alert summary = Alerts.baseline(feed, discordId, matches);
            alerts = intake.enqueueIfAbsent(summary.title(), summary.body(), CHANNEL,
                    summary.externalSource(), summary.externalId(), feed.getUserId()) ? 1 : 0;
        }
        jdbc.update("UPDATE feeds SET baselined_at = now() WHERE id = :id AND baselined_at IS NULL",
                Map.of("id", feed.getId()));
        return alerts;
    }

    /**
     * Links these feeds to the event and returns the ones that were not linked before.
     * The SELECT skips a feed deleted since the poll loaded it, which would otherwise fail
     * its foreign key and take the whole event down with it.
     */
    private List<Long> link(Set<Long> feedIds, long eventId) {
        if (feedIds.isEmpty()) return List.of();
        return jdbc.queryForList("""
                INSERT INTO feed_events (feed_id, event_id)
                SELECT f.id, :eventId FROM feeds f WHERE f.id IN (:feedIds)
                ON CONFLICT DO NOTHING
                RETURNING feed_id
                """, Map.of("eventId", eventId, "feedIds", feedIds), Long.class);
    }

    private Map<Long, String> discordIds(Set<Long> userIds) {
        Map<Long, String> out = new HashMap<>();
        if (userIds.isEmpty()) return out;
        for (User u : users.findAllById(userIds)) out.put(u.getId(), u.getExternalId());
        return out;
    }

    private int enqueue(Alert alert, Long userId, Map<Long, String> discordIds) {
        // A user deleted since their feed was read has nowhere to be told
        if (!discordIds.containsKey(userId)) return 0;
        boolean written = intake.enqueueIfAbsent(
                alert.title(), alert.body(), CHANNEL, alert.externalSource(), alert.externalId(), userId);
        return written ? 1 : 0;
    }
}
