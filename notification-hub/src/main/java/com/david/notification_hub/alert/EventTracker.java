package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.event.StoredEventRepository;
import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.notification_request.NotificationIntakeService;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.user.User;
import com.david.notification_hub.user.UserRepository;
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
 *       polls second must still tell its own subscribers.</li>
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
        Set<Long> newcomers = link(feeds, stored.getId());

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
     * Links the group's feeds to the event and returns the users of the feeds that were
     * not linked before. The SELECT skips a feed deleted since the poll loaded it, which
     * would otherwise fail its foreign key and take the whole event down with it.
     */
    private Set<Long> link(List<Feed> feeds, long eventId) {
        if (feeds.isEmpty()) return Set.of();

        Map<Long, Long> userByFeed = new HashMap<>();
        for (Feed f : feeds) userByFeed.put(f.getId(), f.getUserId());

        List<Long> linked = jdbc.queryForList("""
                INSERT INTO feed_events (feed_id, event_id)
                SELECT f.id, :eventId FROM feeds f WHERE f.id IN (:feedIds)
                ON CONFLICT DO NOTHING
                RETURNING feed_id
                """, Map.of("eventId", eventId, "feedIds", userByFeed.keySet()), Long.class);

        Set<Long> userIds = new HashSet<>();
        for (Long feedId : linked) userIds.add(userByFeed.get(feedId));
        return userIds;
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
