package com.david.notification_hub.outbox;

import com.david.notification_hub.delivery_logs.DeliveryLog;
import com.david.notification_hub.delivery_logs.DeliveryLogRepository;
import com.david.notification_hub.notification_request.NotificationRequest;
import com.david.notification_hub.notification_request.NotificationRequestRepository;
import com.david.notification_hub.notification_request.NotificationStatus;
import com.david.notification_hub.notify.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every database transaction the dispatcher needs, and nothing else.
 *
 * This is a separate bean on purpose. Spring's {@code @Transactional} works through a
 * proxy, so a method calling another method on {@code this} bypasses it entirely - the
 * dispatcher calling its own transactional helpers would silently get no transaction.
 * Keeping the transactional work here means the boundaries are real.
 *
 * The point of the split: each of these commits in milliseconds. The HTTP send happens
 * between them, holding no connection.
 */
@Service
public class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);

    private final NotificationRequestRepository requestRepo;
    private final DeliveryLogRepository logRepo;

    public OutboxStore(NotificationRequestRepository requestRepo, DeliveryLogRepository logRepo) {
        this.requestRepo = requestRepo;
        this.logRepo = logRepo;
    }

    /**
     * Takes ownership of up to {@code limit} due rows and marks them SENDING so no other
     * pass - or other instance - picks them up. REQUIRES_NEW because the row locks taken
     * by the claim query must be released as soon as the claim commits, not held for the
     * length of the sends that follow.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NotificationRequest> claimBatch(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        List<NotificationRequest> due = requestRepo.findDueForUpdate(now, limit);

        for (NotificationRequest r : due) {
            r.setStatus(NotificationStatus.SENDING);
            r.setClaimedAt(now);
        }
        return requestRepo.saveAll(due);
    }

    /** Provider accepted it. Terminal, and the retry fields are cleared. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(long id, SendResult result) {
        NotificationRequest r = requestRepo.findById(id).orElse(null);
        if (r == null) {
            log.warn("Notification {} vanished before its result could be recorded", id);
            return;
        }

        int attempt = r.getAttempts() + 1;
        r.setAttempts(attempt);
        r.setStatus(NotificationStatus.SENT);
        r.setNextAttemptAt(null);
        r.setClaimedAt(null);
        r.setLastError(null);
        requestRepo.save(r);

        writeLog(r, attempt, true, result.response, null);
    }

    /**
     * Send failed. Either schedules another attempt with exponential backoff, or gives
     * up and marks the row DEAD with the error preserved.
     *
     * @return true if the row is now DEAD
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(long id, SendResult result, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        NotificationRequest r = requestRepo.findById(id).orElse(null);
        if (r == null) {
            log.warn("Notification {} vanished before its failure could be recorded", id);
            return false;
        }

        int attempt = r.getAttempts() + 1;
        String error = (result.error == null || result.error.isBlank()) ? "UNKNOWN_ERROR" : result.error;

        r.setAttempts(attempt);
        r.setLastError(error);
        r.setClaimedAt(null);

        boolean dead = attempt >= maxAttempts;
        if (dead) {
            r.setStatus(NotificationStatus.DEAD);
            r.setNextAttemptAt(null);
        } else {
            r.setStatus(NotificationStatus.RETRY);
            r.setNextAttemptAt(OffsetDateTime.now().plus(backoffFor(attempt, baseBackoff, maxBackoff)));
        }
        requestRepo.save(r);

        writeLog(r, attempt, false, null, error);
        return dead;
    }

    /**
     * Rescues rows whose owner died mid-send: back to RETRY, due immediately.
     *
     * Note this can re-send something the provider actually received - the instance may
     * have crashed after the webhook returned but before the result was written. At-least-once
     * is the deliberate trade: a duplicate Discord message is cheaper than a silently
     * dropped one.
     *
     * @return how many rows were requeued
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueStuck(Duration stuckAfter, int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(stuckAfter);
        List<NotificationRequest> stuck = requestRepo.findStuckSending(cutoff, limit);
        if (stuck.isEmpty()) return 0;

        for (NotificationRequest r : stuck) {
            r.setStatus(NotificationStatus.RETRY);
            r.setNextAttemptAt(OffsetDateTime.now());
            r.setClaimedAt(null);
        }
        requestRepo.saveAll(stuck);
        return stuck.size();
    }

    /**
     * Exponential backoff with jitter: base * 2^(attempt-1), capped, then randomised
     * across [50%, 100%]. The jitter matters because a Discord outage fails every row in
     * the batch at once - without it they would all come back in the same instant.
     */
    static Duration backoffFor(int attempt, Duration base, Duration max) {
        long baseMs = base.toMillis();
        int shift = Math.min(attempt - 1, 20); // 2^20 is already far past any sane cap
        long scaled = baseMs << shift;
        if (scaled <= 0 || scaled > max.toMillis()) scaled = max.toMillis();

        long jittered = scaled / 2 + ThreadLocalRandom.current().nextLong(scaled / 2 + 1);
        return Duration.ofMillis(jittered);
    }

    private void writeLog(NotificationRequest r, int attempt, boolean succeeded, String response, String error) {
        DeliveryLog entry = new DeliveryLog();
        entry.setRequest(r);
        entry.setAttempt(attempt);
        entry.setSucceeded(succeeded);
        entry.setProviderResponse(response);
        entry.setErrorMsg(error);
        logRepo.save(entry);
    }
}
