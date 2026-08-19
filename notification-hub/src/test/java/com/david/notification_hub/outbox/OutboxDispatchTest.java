package com.david.notification_hub.outbox;

import com.david.notification_hub.delivery_logs.DeliveryLog;
import com.david.notification_hub.delivery_logs.DeliveryLogRepository;
import com.david.notification_hub.notification_request.NotificationIntakeService;
import com.david.notification_hub.notification_request.NotificationRequest;
import com.david.notification_hub.notification_request.NotificationRequestRepository;
import com.david.notification_hub.notification_request.NotificationStatus;
import com.david.notification_hub.notify.SendResult;
import com.david.notification_hub.notify.SendRouter;
import com.david.notification_hub.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the outbox introduced in V6: enqueue writes a row and sends nothing, the
 * dispatcher drains it, and failures retry with backoff instead of vanishing.
 *
 * The router is mocked - these tests are about the queue's bookkeeping, not about
 * whether Discord accepts a payload, and nothing here should make a real webhook call.
 *
 * Deliberately not @Transactional: OutboxStore's methods are REQUIRES_NEW, so a
 * test-managed transaction would not see their commits.
 */
@SpringBootTest
class OutboxDispatchTest extends AbstractPostgresTest {

    @Autowired NotificationIntakeService intake;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired OutboxStore store;
    @Autowired NotificationRequestRepository requestRepo;
    @Autowired DeliveryLogRepository logRepo;

    @MockBean SendRouter router;

    @BeforeEach
    void clean() {
        // delivery_logs holds an FK to notification_requests, so it goes first
        logRepo.deleteAll();
        requestRepo.deleteAll();
    }

    private NotificationRequest enqueue(String externalId, String channel) {
        return intake.enqueue("title", "body", "NORMAL", channel, "canvas:test", externalId).request();
    }

    private NotificationRequest reload(long id) {
        return requestRepo.findById(id).orElseThrow();
    }

    // ---------- intake ----------

    @Test
    void enqueueStoresQueuedAndSendsNothing() {
        NotificationRequest r = enqueue("enq-1", "DISCORD");

        assertEquals(NotificationStatus.QUEUED, r.getStatus());
        assertEquals(0, r.getAttempts());
        assertNotNull(r.getNextAttemptAt(), "must be due, or the dispatcher will never claim it");
        // The old controller sent inline; enqueueing must not touch the router at all
        verifyNoInteractions(router);
    }

    @Test
    void enqueueingTheSameItemTwiceReturnsTheFirstRow() {
        NotificationRequest first = enqueue("dup-1", "DISCORD");
        NotificationIntakeService.Result second =
                intake.enqueue("title", "body", "NORMAL", "DISCORD", "canvas:test", "dup-1");

        assertTrue(second.duplicate());
        assertEquals(first.getId(), second.request().getId());
        assertEquals(1, requestRepo.count(), "the duplicate must not create a second row");
    }

    // ---------- happy path ----------

    @Test
    void dispatcherSendsQueuedRowAndMarksItSent() {
        when(router.send(eq("DISCORD"), any(), any(), any(), anyBoolean()))
                .thenReturn(SendResult.ok("Discord OK 204"));

        long id = enqueue("ok-1", "DISCORD").getId();
        dispatcher.dispatchDue();

        NotificationRequest after = reload(id);
        assertEquals(NotificationStatus.SENT, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertNull(after.getNextAttemptAt(), "a sent row is terminal and must not stay due");
        assertNull(after.getClaimedAt());
        assertNull(after.getLastError());

        List<DeliveryLog> logs = logRepo.findAll();
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).getSucceeded());
        assertEquals("Discord OK 204", logs.get(0).getProviderResponse());
    }

    @Test
    void sentRowIsNotSentAgainOnTheNextPass() {
        when(router.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(SendResult.ok("ok"));

        enqueue("once-1", "DISCORD");
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        verify(router, org.mockito.Mockito.times(1))
                .send(any(), any(), any(), any(), anyBoolean());
    }

    // ---------- failure path ----------

    @Test
    void failedSendGoesToRetryWithABackoffAndKeepsTheError() {
        when(router.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(SendResult.fail("Discord HTTP 500"));

        long id = enqueue("fail-1", "DISCORD").getId();
        dispatcher.dispatchDue();

        NotificationRequest after = reload(id);
        assertEquals(NotificationStatus.RETRY, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertEquals("Discord HTTP 500", after.getLastError());
        assertNotNull(after.getNextAttemptAt());
        assertTrue(after.getNextAttemptAt().isAfter(OffsetDateTime.now()),
                "a retry due in the past would spin instead of backing off");

        assertEquals(1, logRepo.count());
        assertFalse(logRepo.findAll().get(0).getSucceeded());
    }

    @Test
    void aRowThatIsNotYetDueIsSkipped() {
        when(router.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(SendResult.fail("nope"));

        long id = enqueue("later-1", "DISCORD").getId();
        dispatcher.dispatchDue();            // attempt 1 -> RETRY, due ~30s out
        dispatcher.dispatchDue();            // must not touch it again yet

        assertEquals(1, reload(id).getAttempts());
    }

    @Test
    void attemptsAreExhaustedIntoDead() {
        when(router.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(SendResult.fail("still broken"));

        long id = enqueue("dead-1", "DISCORD").getId();

        // maxAttempts defaults to 5. Force each retry to be due rather than waiting
        // out the real backoff.
        for (int i = 0; i < 5; i++) {
            NotificationRequest due = reload(id);
            due.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
            requestRepo.save(due);
            dispatcher.dispatchDue();
        }

        NotificationRequest after = reload(id);
        assertEquals(NotificationStatus.DEAD, after.getStatus());
        assertEquals(5, after.getAttempts());
        assertEquals("still broken", after.getLastError());
        assertNull(after.getNextAttemptAt(), "a dead row must not stay due");
        assertEquals(5, logRepo.count(), "every attempt should leave a delivery log");
    }

    @Test
    void aSenderThatThrowsIsTreatedAsAFailureNotACrash() {
        when(router.send(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        long id = enqueue("throw-1", "DISCORD").getId();
        dispatcher.dispatchDue();   // must not propagate

        NotificationRequest after = reload(id);
        assertEquals(NotificationStatus.RETRY, after.getStatus());
        assertTrue(after.getLastError().contains("boom"));
    }

    // ---------- reaper ----------

    @Test
    void rowsOrphanedMidSendAreRequeued() {
        long id = enqueue("stuck-1", "DISCORD").getId();

        // Simulate an instance that claimed the row and then died
        NotificationRequest claimed = reload(id);
        claimed.setStatus(NotificationStatus.SENDING);
        claimed.setClaimedAt(OffsetDateTime.now().minusHours(1));
        requestRepo.save(claimed);

        int requeued = store.requeueStuck(Duration.ofMinutes(5), 25);

        assertEquals(1, requeued);
        NotificationRequest after = reload(id);
        assertEquals(NotificationStatus.RETRY, after.getStatus());
        assertNull(after.getClaimedAt());
    }

    @Test
    void aRecentlyClaimedRowIsLeftAlone() {
        long id = enqueue("inflight-1", "DISCORD").getId();

        NotificationRequest claimed = reload(id);
        claimed.setStatus(NotificationStatus.SENDING);
        claimed.setClaimedAt(OffsetDateTime.now());   // still in flight
        requestRepo.save(claimed);

        assertEquals(0, store.requeueStuck(Duration.ofMinutes(5), 25),
                "reaping an in-flight row would double-send it");
    }

    // ---------- claiming ----------

    @Test
    void claimingMarksRowsSendingSoAnotherPassSkipsThem() {
        enqueue("claim-1", "DISCORD");
        enqueue("claim-2", "SLACK");

        List<NotificationRequest> first = store.claimBatch(25);
        assertEquals(2, first.size());
        assertTrue(first.stream().allMatch(r -> NotificationStatus.SENDING.equals(r.getStatus())));

        assertTrue(store.claimBatch(25).isEmpty(),
                "a second dispatcher must not re-claim rows already in flight");
    }

    @Test
    void batchSizeIsRespected() {
        for (int i = 0; i < 5; i++) enqueue("batch-" + i, "DISCORD");

        assertEquals(2, store.claimBatch(2).size());
    }
}
