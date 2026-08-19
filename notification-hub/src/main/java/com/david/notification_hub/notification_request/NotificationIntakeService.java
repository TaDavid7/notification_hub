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
 * Both the REST controller and {@code CanvasPoller} come through here. The poller
 * used to call {@code NotificationRequestController.create(...)} directly, which
 * meant a scheduled job was constructing DTOs and interpreting ResponseEntity
 * status codes to decide whether its own write had worked.
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
     * @param duplicate true when this (source, id, channel) was already enqueued
     */
    public record Result(NotificationRequest request, boolean duplicate) {}

    /**
     * Deliberately not {@code @Transactional}. The duplicate path relies on the unique
     * index throwing, and a rolled-back transaction cannot then be used to read the
     * existing row - the lookup has to happen in a fresh one.
     */
    public Result enqueue(String title,
                          String body,
                          String priority,
                          String channel,
                          String externalSource,
                          String externalId) {

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
        // Due immediately; the dispatcher's next pass will claim it.
        r.setNextAttemptAt(OffsetDateTime.now());

        try {
            return new Result(repo.saveAndFlush(r), false);
        } catch (DataIntegrityViolationException dup) {
            // Unique index hit -> this Canvas item was already enqueued for this
            // channel. Idempotent success: hand back the row that won.
            Optional<NotificationRequest> existing =
                    repo.findByExternalSourceAndExternalIdAndChannel(externalSource, externalId, ch);

            if (existing.isPresent()) {
                return new Result(existing.get(), true);
            }

            // Constraint fired but the row isn't there - not the idempotency case,
            // so don't swallow it.
            log.error("Duplicate key on ({}, {}, {}) but no existing row found",
                    externalSource, externalId, ch, dup);
            throw dup;
        }
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
