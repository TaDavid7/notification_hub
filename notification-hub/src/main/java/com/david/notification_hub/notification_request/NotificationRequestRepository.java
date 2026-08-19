package com.david.notification_hub.notification_request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, Long> {
    Optional<NotificationRequest> findByExternalSourceAndExternalIdAndChannel(String externalSource, String externalId, String channel);

    /**
     * Claims a batch of due outbox rows.
     *
     * {@code FOR UPDATE SKIP LOCKED} is what makes this safe to run on more than one
     * instance: each dispatcher locks the rows it takes and simply steps over rows
     * another instance already holds, instead of blocking or double-sending. That is
     * also why the Redis/ShedLock item in plan.md is only needed for CanvasPoller and
     * not for this loop.
     *
     * Must be called inside a transaction - the locks live until it commits.
     */
    @Query(value = """
            SELECT * FROM notification_requests
             WHERE status IN ('QUEUED', 'RETRY')
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationRequest> findDueForUpdate(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * Rows an instance claimed and never finished - it crashed, was scaled down, or
     * the container was replaced mid-send. Without this they would sit in SENDING
     * forever, since only a claim moves them on.
     */
    @Query(value = """
            SELECT * FROM notification_requests
             WHERE status = 'SENDING'
               AND claimed_at < :cutoff
             ORDER BY claimed_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationRequest> findStuckSending(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
