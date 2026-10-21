package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.source.EventStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The alertable fields of a stored event, captured before an update overwrites them so
 * the update alert can say what each one was.
 */
public record EventSnapshot(
        OffsetDateTime startsAt,
        OffsetDateTime onSaleAt,
        String venue,
        BigDecimal price,
        EventStatus status) {

    public static EventSnapshot of(StoredEvent e) {
        return new EventSnapshot(e.getStartsAt(), e.getOnSaleAt(), e.getVenue(), e.getPrice(), e.getStatus());
    }
}
