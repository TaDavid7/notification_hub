package com.david.notification_hub.notification_request;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * The one way a notification enters the outbox.
 *
 * Every producer comes through here, the REST controller included. A scheduled job
 * should call this rather than the controller, so it never has to construct DTOs or
 * interpret ResponseEntity status codes to decide whether its own write had worked.
 *
 * Enqueueing only writes the row. Nothing is sent on this thread - the dispatcher
 * picks it up. That is what keeps a slow webhook out of the request path.
 */
@Service
public class NotificationIntakeService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIntakeService.class);

    private final NotificationRequestRepository repo;

    public NotificationIntakeService(NotificationRequestRepository repo) {
        this.repo = repo;
    }

    /**
     * @param request   the stored row, whether freshly created or the pre-existing one
     * @param duplicate true when this (source, id, channel, user) was already enqueued
     */
    public record Result(NotificationRequest request, boolean duplicate) {}

    /** Enqueues a notification for no particular user - the REST API's path. */
    public Result enqueue(String title,
                          String body,
                          String priority,
                          String channel,
                          String externalSource,
                          String externalId) {
        return enqueue(title, body, priority, channel, externalSource, externalId, null);
    }

    /**
     * Deliberately not {@code @Transactional}. The duplicate path relies on the unique
     * index throwing, and a rolled-back transaction cannot then be used to read the
     * existing row - the lookup has to happen in a fresh one.
     *
     * @param userId the subscriber this is for, or null when there is none
     */
    public Result enqueue(String title,
                          String body,
                          String priority,
                          String channel,
                          String externalSource,
                          String externalId,
                          Long userId) {

        String ch   = blankTo(channel, "DISCORD").toUpperCase(Locale.ROOT);
        String prio = blankTo(priority, "NORMAL").toUpperCase(Locale.ROOT);

        NotificationRequest r = new NotificationRequest();
        r.setTitle(title);
        r.setBody(body);
        r.setPriority(prio);
        r.setChannel(ch);
        r.setStatus(NotificationStatus.QUEUED);
        r.setExternalSource(externalSource);
        r.setExternalId(externalId);
        r.setUserId(userId);
        // Due immediately; the dispatcher's next pass will claim it.
        r.setNextAttemptAt(OffsetDateTime.now());

        try {
            return new Result(repo.saveAndFlush(r), false);
        } catch (DataIntegrityViolationException dup) {
            // Unique index hit -> this source item was already enqueued for this
            // channel and user. Idempotent success: hand back the row that won.
            Optional<NotificationRequest> existing =
                    repo.findByExternalSourceAndExternalIdAndChannelAndUserId(externalSource, externalId, ch, userId);

            if (existing.isPresent()) {
                return new Result(existing.get(), true);
            }

            // Constraint fired but the row isn't there - not the idempotency case (an
            // unknown user_id fails its foreign key the same way), so don't swallow it.
            log.error("Integrity violation on ({}, {}, {}, {}) but no existing row found",
                    externalSource, externalId, ch, userId, dup);
            throw dup;
        }
    }

    /**
     * Enqueues inside the caller's transaction, for a producer whose notification has to
     * commit or roll back together with the change it reports. Unlike {@link #enqueue}, a
     * duplicate is not an exception and does not poison that transaction.
     *
     * Runs in a transaction of its own when there is none to join.
     *
     * @return true if a row was written, false if this key was already enqueued
     */
    public boolean enqueueIfAbsent(String title,
                                   String body,
                                   String channel,
                                   String externalSource,
                                   String externalId,
                                   Long userId) {
        String ch = blankTo(channel, "DISCORD").toUpperCase(Locale.ROOT);
        return repo.insertIfAbsent(title, body, ch, externalSource, externalId, userId, OffsetDateTime.now()) == 1;
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
