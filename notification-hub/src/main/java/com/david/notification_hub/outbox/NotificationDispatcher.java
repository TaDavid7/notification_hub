package com.david.notification_hub.outbox;

import com.david.notification_hub.notification_request.NotificationRequest;
import com.david.notification_hub.notify.SendResult;
import com.david.notification_hub.notify.SendRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Drains the outbox.
 *
 * The loop is deliberately boring: claim a batch in one short transaction, send each
 * one with no transaction open, write each outcome back in another short transaction.
 * A webhook that takes its full 10s timeout now costs a slot in this worker rather
 * than a pooled DB connection and a waiting HTTP client.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final OutboxStore store;
    private final SendRouter router;

    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration stuckAfter;

    public NotificationDispatcher(
            OutboxStore store,
            SendRouter router,
            @Value("${notifications.dispatch.batchSize:25}") int batchSize,
            @Value("${notifications.dispatch.maxAttempts:5}") int maxAttempts,
            @Value("${notifications.dispatch.baseBackoffSeconds:30}") long baseBackoffSeconds,
            @Value("${notifications.dispatch.maxBackoffSeconds:3600}") long maxBackoffSeconds,
            @Value("${notifications.dispatch.stuckAfterSeconds:300}") long stuckAfterSeconds) {
        this.store = store;
        this.router = router;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofSeconds(baseBackoffSeconds);
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
        this.stuckAfter = Duration.ofSeconds(stuckAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${notifications.dispatch.delayMs:5000}",
               initialDelayString = "${notifications.dispatch.initialDelayMs:10000}")
    public void dispatchDue() {
        try {
            List<NotificationRequest> batch = store.claimBatch(batchSize);
            if (batch.isEmpty()) return;

            log.debug("Dispatching {} notification(s)", batch.size());
            for (NotificationRequest req : batch) {
                dispatchOne(req);
            }
        } catch (Exception ex) {
            // A scheduled method that throws is simply not rescheduled by some
            // executors, which would stop delivery permanently. Swallow at the top.
            log.error("Dispatch pass failed", ex);
        }
    }

    private void dispatchOne(NotificationRequest req) {
        long id = req.getId();
        SendResult result;
        try {
            // No transaction is open here. This is the whole point of the outbox.
            result = router.send(req.getChannel(), "", req.getTitle(), req.getBody(), true);
        } catch (Exception ex) {
            // Senders are written to return SendResult.fail rather than throw, but a
            // bug or a RuntimeException must not take the rest of the batch with it.
            log.warn("Sender threw for notification {} on {}", id, req.getChannel(), ex);
            result = SendResult.fail(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        try {
            if (result.success) {
                store.recordSuccess(id, result);
                log.debug("Notification {} sent on {}", id, req.getChannel());
            } else {
                boolean dead = store.recordFailure(id, result, maxAttempts, baseBackoff, maxBackoff);
                if (dead) {
                    log.error("Notification {} on {} is DEAD after {} attempts: {}",
                            id, req.getChannel(), maxAttempts, result.error);
                } else {
                    log.warn("Notification {} on {} failed, will retry: {}",
                            id, req.getChannel(), result.error);
                }
            }
        } catch (Exception ex) {
            // Couldn't write the outcome. The row stays SENDING and the reaper below
            // will pick it up rather than it being lost.
            log.error("Could not record outcome for notification {}; leaving it for the reaper", id, ex);
        }
    }

    /**
     * Returns rows orphaned by a crashed instance to the queue. Runs far less often
     * than the dispatcher because it only matters after a hard failure.
     */
    @Scheduled(fixedDelayString = "${notifications.reaper.delayMs:60000}",
               initialDelayString = "${notifications.reaper.initialDelayMs:60000}")
    public void requeueStuck() {
        try {
            int requeued = store.requeueStuck(stuckAfter, batchSize);
            if (requeued > 0) {
                log.warn("Requeued {} notification(s) stuck in SENDING for over {}", requeued, stuckAfter);
            }
        } catch (Exception ex) {
            log.error("Reaper pass failed", ex);
        }
    }
}
