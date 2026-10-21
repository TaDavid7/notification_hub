package com.david.pingr.polling;

import com.david.pingr.alert.ChangeDetector;
import com.david.pingr.feed.Feed;
import com.david.pingr.source.Event;
import com.david.pingr.source.SourceException;
import com.david.pingr.source.SourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Polls the sources. Each pass syncs the groups with the feeds, claims those that are
 * due, and fetches each one once for all of its feeds.
 *
 * Failures stay with their group: an API error backs that group off and the pass moves
 * on to the next. Our own rate limit running out defers a group instead, since nothing
 * actually failed. Safe on several instances, as the claim is SKIP LOCKED.
 */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final PollStore store;
    private final SourceRegistry sources;
    private final ChangeDetector detector;
    private final PollingProperties props;

    public PollScheduler(PollStore store, SourceRegistry sources, ChangeDetector detector, PollingProperties props) {
        this.store = store;
        this.sources = sources;
        this.detector = detector;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${polling.delayMs:60000}",
               initialDelayString = "${polling.initialDelayMs:30000}")
    public void run() {
        try {
            pollDue();
        } catch (Exception ex) {
            // Same reasoning as the dispatcher: a scheduled method that throws may never
            // be scheduled again
            log.error("Poll pass failed", ex);
        }
    }

    /**
     * One pass. Public so a test can run one without waiting for the schedule.
     *
     * @return how many groups were claimed
     */
    public int pollDue() {
        store.syncGroups();
        List<PollGroup> due = store.claimDue(sources.types(), props.lease(), props.batchSize());
        for (PollGroup group : due) poll(group);
        return due.size();
    }

    private void poll(PollGroup group) {
        List<Feed> feeds = store.feedsOf(group);
        if (feeds.isEmpty()) {
            // Everyone unsubscribed since the claim; the next sync drops the group
            return;
        }

        List<Event> events;
        try {
            // Any feed will do: they all share the params, which are all a source may use
            events = sources.get(group.sourceType()).fetch(feeds.get(0));
        } catch (SourceException ex) {
            if (ex.isThrottled()) {
                store.defer(group, ex.getRetryAfter(), ex.getMessage());
                log.info("Deferred {} group {}: {}", group.sourceType(), group.id(), ex.getMessage());
            } else {
                Duration wait = store.recordFailure(group, ex.getMessage(), ex.getRetryAfter(),
                        props.baseBackoff(), props.maxBackoff());
                log.warn("Polling {} group {} failed ({} in a row); next try in {}: {}",
                        group.sourceType(), group.id(), group.failures() + 1, wait, ex.getMessage());
            }
            return;
        } catch (RuntimeException ex) {
            // A feed with bad params, or a bug in a source - its group backs off, the rest carry on
            Duration wait = store.recordFailure(group, ex.toString(), null, props.baseBackoff(), props.maxBackoff());
            log.warn("Polling {} group {} failed; next try in {}", group.sourceType(), group.id(), wait, ex);
            return;
        }

        ChangeDetector.Summary summary = detector.apply(feeds, events);
        store.recordSuccess(group, props.intervalFor(group.sourceType()));

        if (summary.alerts() > 0 || summary.failed() > 0) {
            log.info("Polled {} group {} for {} feed(s): {}", group.sourceType(), group.id(), feeds.size(), summary);
        } else {
            log.debug("Polled {} group {} for {} feed(s): {}", group.sourceType(), group.id(), feeds.size(), summary);
        }
    }
}
