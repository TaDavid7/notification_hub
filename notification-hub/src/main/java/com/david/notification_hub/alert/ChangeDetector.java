package com.david.notification_hub.alert;

import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Takes one poll's results for one group of feeds and works out what is new and what
 * changed, alerting subscribers through the outbox.
 *
 * Each event is its own transaction (see {@link EventTracker}). One that fails rolls
 * back alone and the next poll retries it; the rest of the fetch still lands.
 *
 * A feed on its first poll gets one summary of what it found instead of a NEW alert per
 * item: subscribing to concerts near a city would otherwise announce hundreds of shows
 * that were already on sale.
 */
@Service
public class ChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(ChangeDetector.class);

    private final EventTracker tracker;

    public ChangeDetector(EventTracker tracker) {
        this.tracker = tracker;
    }

    /** @param baselined how many feeds finished their first poll */
    public record Summary(int fetched, int created, int changed, int alerts, int failed, int baselined) {}

    /**
     * @param feeds   the group the fetch was made for: same source type, same params
     * @param fetched what the source returned
     */
    public Summary apply(List<Feed> feeds, List<Event> fetched) {
        if (feeds.isEmpty()) return new Summary(fetched.size(), 0, 0, 0, 0, 0);
        SourceType type = feeds.get(0).getSourceType();

        int created = 0, changed = 0, alerts = 0, failed = 0, baselined = 0;
        List<Event> matched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Event event : fetched) {
            if (event.source() != type) {
                // A source breaking its contract; storing it under the wrong feeds would
                // alert people about things they never asked for
                failed++;
                log.warn("Ignoring {} event {} returned for {} feeds", event.source(), event.externalId(), type);
                continue;
            }
            // Paging can return an item twice when results shift between pages
            if (!seen.add(event.externalId())) continue;
            matched.add(event);

            try {
                EventTracker.Outcome outcome = tracker.record(feeds, event);
                if (outcome.created()) created++;
                if (outcome.changed()) changed++;
                alerts += outcome.alerts();
            } catch (Exception ex) {
                failed++;
                log.warn("Could not record {} event {}; the next poll will retry it",
                        event.source(), event.externalId(), ex);
            }
        }

        // After the events, so the links the summary implies already exist. Runs even when
        // nothing matched: "nothing right now" still tells a new subscriber it's working.
        for (Feed feed : feeds) {
            if (feed.getBaselinedAt() != null) continue;
            try {
                alerts += tracker.finishBaseline(feed, matched);
                baselined++;
            } catch (Exception ex) {
                failed++;
                log.warn("Could not finish the first poll of feed {}; the next poll will retry it", feed.getId(), ex);
            }
        }

        Summary summary = new Summary(fetched.size(), created, changed, alerts, failed, baselined);
        log.debug("{} poll for {} feed(s): {}", type, feeds.size(), summary);
        return summary;
    }
}
