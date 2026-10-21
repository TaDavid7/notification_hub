package com.david.notification_hub.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One item as a source returns it on a single poll, normalised so two polls describing
 * the same thing produce equal values. Not an entity: what is kept between polls is
 * {@code StoredEvent}.
 *
 * @param source     the source that returned it
 * @param externalId the source's own id; together with source, identifies the item across polls
 * @param title      required
 * @param startsAt   null when there is no date, e.g. a game deal
 * @param onSaleAt   when tickets go on general sale; null if unknown or not applicable
 * @param venue      null when not applicable
 * @param price      rounded to cents, the precision events.price stores
 * @param url        where to send the user
 * @param status     whether it is still going ahead; null is taken as SCHEDULED
 */
public record Event(
        SourceType source,
        String externalId,
        String title,
        OffsetDateTime startsAt,
        OffsetDateTime onSaleAt,
        String venue,
        BigDecimal price,
        String url,
        EventStatus status) {

    public Event {
        Objects.requireNonNull(source, "source");
        externalId = required(externalId, "externalId");
        title = required(title, "title");
        venue = blankToNull(venue);
        url = blankToNull(url);
        // Rounded here, not just by the column, so the hash covers exactly the price that
        // gets stored: 49.5 and 49.50 are one price, and a sub-cent wobble is no change.
        price = price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
        status = status == null ? EventStatus.SCHEDULED : status;
    }

    /**
     * A fingerprint of what a subscriber would want to hear has changed: the dates, the
     * venue, the price and the status. The status is the only sign of a cancellation,
     * which can leave every other field as it was. Title and url are left out because
     * sources retitle and re-link items without anything real changing, and each of those
     * would be an alert.
     *
     * Changing what goes in here changes every hash, so the first poll afterwards would
     * report every known event as updated.
     */
    public String contentHash() {
        StringBuilder sb = new StringBuilder();
        // Instants, so the same moment written with two different offsets is no change
        field(sb, startsAt == null ? null : startsAt.toInstant());
        field(sb, onSaleAt == null ? null : onSaleAt.toInstant());
        field(sb, venue);
        field(sb, price == null ? null : price.toPlainString());
        field(sb, status.name());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException(e);
        }
    }

    // Length-prefixed, so no value - a venue name can contain anything - can be read
    // as a field boundary, and a missing value is distinct from any present one
    private static void field(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("-;");
            return;
        }
        String s = value.toString();
        sb.append(s.length()).append(':').append(s).append(';');
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
