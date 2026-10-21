package com.david.notification_hub.notification_request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, Long> {
    /**
     * The row holding a given dedupe key. A null {@code userId} matches rows with no
     * user, since Spring Data derives {@code user_id IS NULL} for a null argument -
     * which is what the REST API's duplicate handling needs.
     */
    Optional<NotificationRequest> findByExternalSourceAndExternalIdAndChannelAndUserId(
            String externalSource, String externalId, String channel, Long userId);

    /**
     * Writes a due QUEUED row unless its dedupe key is already taken.
     *
     * ON CONFLICT DO NOTHING rather than letting the unique index throw: in Postgres a
     * failed statement aborts the whole surrounding transaction, and the caller's
     * transaction also holds the change this notification reports.
     *
     * @return 1 if the row was written, 0 if the key already existed
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO notification_requests
                   (title, body, priority, status, channel, external_source, external_id, user_id,
                    attempts, next_attempt_at, created_at)
            VALUES (:title, :body, 'NORMAL', 'QUEUED', :channel, :externalSource, :externalId, :userId,
                    0, :now, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("title") String title,
                       @Param("body") String body,
                       @Param("channel") String channel,
                       @Param("externalSource") String externalSource,
                       @Param("externalId") String externalId,
                       @Param("userId") Long userId,
                       @Param("now") OffsetDateTime now);

    /**
     * Claims a batch of due outbox rows.
     *
     * {@code FOR UPDATE SKIP LOCKED} is what makes this safe to run on more than one
     * instance: each dispatcher locks the rows it takes and simply steps over rows
     * another instance already holds, instead of blocking or double-sending. That is
     * also why this loop needs no ShedLock-style leader election.
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
