package com.david.pingr.alert;

import com.david.pingr.event.StoredEvent;
import com.david.pingr.event.StoredEventRepository;
import com.david.pingr.notification_request.NotificationIntakeService;
import com.david.pingr.source.EventStatus;
import com.david.pingr.user.User;
import com.david.pingr.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * "Starting soon" and "on sale soon" reminders, for everyone following an event.
 *
 * Driven by the clock rather than by polls: an event can sit unchanged for months and
 * still need a reminder on the day. Each pass finds events whose time falls inside the
 * window ahead and enqueues a reminder per follower. The outbox key carries that time,
 * so later passes over the same window send nothing more, while a rescheduled event is
 * reminded again for its new date. Safe on several instances for the same reason.
 *
 * Cancelled and postponed events are skipped.
 */
@Component
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    private static final List<String> GOING_AHEAD =
            List.of(EventStatus.SCHEDULED.name(), EventStatus.RESCHEDULED.name());

    private static final String STARTING = """
            SELECT DISTINCT fe.event_id, f.user_id
              FROM events e
              JOIN feed_events fe ON fe.event_id = e.id
              JOIN feeds f        ON f.id = fe.feed_id
             WHERE e.starts_at > :from AND e.starts_at <= :to
               AND e.status IN (:goingAhead)
            """;

    private static final String ON_SALE = """
            SELECT DISTINCT fe.event_id, f.user_id
              FROM events e
              JOIN feed_events fe ON fe.event_id = e.id
              JOIN feeds f        ON f.id = fe.feed_id
             WHERE e.on_sale_at > :from AND e.on_sale_at <= :to
               AND e.status IN (:goingAhead)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final StoredEventRepository events;
    private final UserRepository users;
    private final NotificationIntakeService intake;
    private final Duration startingSoon;
    private final Duration onSaleSoon;

    public ReminderJob(NamedParameterJdbcTemplate jdbc,
                       StoredEventRepository events,
                       UserRepository users,
                       NotificationIntakeService intake,
                       @Value("${alerts.reminders.startingSoonHours:24}") long startingSoonHours,
                       @Value("${alerts.reminders.onSaleSoonMinutes:60}") long onSaleSoonMinutes) {
        this.jdbc = jdbc;
        this.events = events;
        this.users = users;
        this.intake = intake;
        this.startingSoon = Duration.ofHours(startingSoonHours);
        this.onSaleSoon = Duration.ofMinutes(onSaleSoonMinutes);
    }

    @Scheduled(fixedDelayString = "${alerts.reminders.delayMs:900000}",
               initialDelayString = "${alerts.reminders.initialDelayMs:60000}")
    public void run() {
        try {
            int sent = runAt(OffsetDateTime.now());
            if (sent > 0) log.info("Enqueued {} reminder(s)", sent);
        } catch (Exception ex) {
            // Same reasoning as the dispatcher: a scheduled method that throws may never
            // be scheduled again
            log.error("Reminder pass failed", ex);
        }
    }

    /**
     * One pass as of {@code now}. Public so a test can pick the moment.
     *
     * @return how many reminders were enqueued; repeats of earlier passes don't count
     */
    public int runAt(OffsetDateTime now) {
        return remind(STARTING, now, startingSoon, Alerts::startingSoon)
                + remind(ON_SALE, now, onSaleSoon, Alerts::onSaleSoon);
    }

    private int remind(String sql, OffsetDateTime now, Duration window,
                       BiFunction<StoredEvent, String, Alert> reminder) {
        List<Map<String, Object>> due = jdbc.queryForList(sql, Map.of(
                "from", now, "to", now.plus(window), "goingAhead", GOING_AHEAD));
        if (due.isEmpty()) return 0;

        Set<Long> eventIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (Map<String, Object> row : due) {
            eventIds.add(((Number) row.get("event_id")).longValue());
            userIds.add(((Number) row.get("user_id")).longValue());
        }
        Map<Long, StoredEvent> eventById = new HashMap<>();
        for (StoredEvent e : events.findAllById(eventIds)) eventById.put(e.getId(), e);
        Map<Long, String> discordIdByUser = new HashMap<>();
        for (User u : users.findAllById(userIds)) discordIdByUser.put(u.getId(), u.getExternalId());

        int sent = 0;
        for (Map<String, Object> row : due) {
            long userId = ((Number) row.get("user_id")).longValue();
            StoredEvent event = eventById.get(((Number) row.get("event_id")).longValue());
            String discordId = discordIdByUser.get(userId);
            // Either may have been deleted between the query and here
            if (event == null || discordId == null) continue;

            Alert alert = reminder.apply(event, discordId);
            if (intake.enqueueIfAbsent(alert.title(), alert.body(), "DISCORD",
                    alert.externalSource(), alert.externalId(), userId)) {
                sent++;
            }
        }
        return sent;
    }
}
